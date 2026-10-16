package org.nexus.signing.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.LongSupplier;

/**
 * 共享 nonce 原子分配器（#33）。
 *
 * <p>取代"每个 JVM 自己读 {@link NoncePool#getMaxNonce} 再事后 {@code add}"的分配方式。旧写法有两个
 * 独立缺陷，本类一并消掉：
 * <ol>
 *   <li><b>读不是认领</b>：{@code getMaxNonce} 返回 {@code TreeMap.lastKey()}，不摘除也不占位，
 *       而分配动作跨两次调用（读 → 签名 → {@code ++} → 写回），调用点无同步 ⇒ <b>同一副本内</b>
 *       两个并发签名会拿到同一个 nonce（{@link NoncePoolAllocationPatternTest} 把这个事实钉成断言）。</li>
 *   <li><b>池不跨进程</b>：prod {@code replicaCount=3}、无按地址亲和，每副本一份池 +
 *       pod 本地 LevelDB ⇒ 跨副本同样会拿到同一个 nonce。且 LevelDB 每次写入的是<b>整池 JSON</b>
 *       （最后写赢）。</li>
 * </ol>
 *
 * <p>认领在<b>一个事务</b>内完成 {@code UPDATE next_nonce = next_nonce + 1} 后读回
 * {@code next_nonce - 1}：InnoDB 的 UPDATE 持行级排他锁到提交，并发认领因此串行，
 * 不可能返回同一值。事务边界用 {@link TransactionTemplate} 显式划定（仓内既有原语：
 * {@code AccountService}、{@code ThreePhaseExecutionTemplate} 等 6 处），不依赖代理，
 * 也就不会出现"自调用绕过 @Transactional"这种静默失效。</p>
 *
 * <p><b>失败即拒单，不静默回退</b>：DB 不可用/行拿不到时抛 {@link NonceAllocationException}。
 * 回退到进程内计数正好把本类要修的重复 nonce 窗口重新打开——与 #112 把审批 store 的
 * "jpa 而仓储缺失静默回退内存"改成拒绝启动是同一口径：<b>宁可拒单一笔，不可两笔同号</b>。</p>
 */
@Component
public class NonceAllocator {

    private static final Logger log = LoggerFactory.getLogger(NonceAllocator.class);

    /** "尚未播种"哨兵：首次认领时以链上 RPC 读到的 nonce 播种，播种是条件 UPDATE（只有一个赢家）。 */
    static final long UNSEEDED = -1L;

    /** 认领最多重试几次（应对并发播种方尚未提交时的空递增）。 */
    private static final int MAX_ATTEMPTS = 3;

    private static final String ENSURE_ROW =
            "INSERT INTO nonce_allocation (address, next_nonce, updated_at) "
                    + "SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM nonce_allocation WHERE address = ?)";

    private static final String SEED_CAS =
            "UPDATE nonce_allocation SET next_nonce = ?, updated_at = ? "
                    + "WHERE address = ? AND next_nonce = ?";

    private static final String BUMP =
            "UPDATE nonce_allocation SET next_nonce = next_nonce + 1, updated_at = ? "
                    + "WHERE address = ? AND next_nonce >= 0";

    private static final String READ_NEXT =
            "SELECT next_nonce FROM nonce_allocation WHERE address = ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    public NonceAllocator(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
    }

    /**
     * 原子认领该地址的下一个 nonce。
     *
     * @param address   发送方地址（本仓所有转账共用唯一平台地址，见 {@code TxController} 的
     *                  {@code pubkey_mismatch} 校验）
     * @param chainSeed 链上当前 nonce 的取值器——<b>仅在该地址首次认领（哨兵态）时调用</b>，
     *                  平时不产生 RPC 开销。取值失败应直接抛异常，由上层映射成对调用方的失败响应。
     * @return 本次认领到的 nonce（调用方是唯一持有者，可安全用于签名广播）
     * @throws NonceAllocationException DB 不可用、行无法递增或读到非法水位
     */
    public long claim(String address, LongSupplier chainSeed) {
        Long allocated = txTemplate.execute(status -> doClaim(address, chainSeed));
        if (allocated == null) {
            throw new NonceAllocationException("nonce 认领返回空事务结果: address=" + address);
        }
        return allocated;
    }

    private Long doClaim(String address, LongSupplier chainSeed) {
        try {
            jdbc.update(ENSURE_ROW, address, UNSEEDED, now(), address);
        } catch (org.springframework.dao.DuplicateKeyException concurrentFirstCreator) {
            // 并发首次建行的正常结果：主键挡住了一方，行已经存在，直接走后面的认领路径。
            log.debug("nonce_allocation 行由并发方先建: address={}", address);
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Long next = readNext(address);
            if (next == null) {
                throw new NonceAllocationException("nonce_allocation 行缺失: address=" + address);
            }
            if (next == UNSEEDED) {
                long seed;
                try {
                    seed = chainSeed.getAsLong();
                } catch (RuntimeException seedFailed) {
                    // 契约：发放失败一律以 NonceAllocationException 表达。调用方只需 catch 这一个
                    // 类型就能保证"要么拿到唯一 nonce，要么这笔不发"——不必关心失败源自 RPC 还是 DB。
                    throw new NonceAllocationException(
                            "播种链上 nonce 失败，拒绝发放: address=" + address + ", 原因=" + seedFailed, seedFailed);
                }
                int won = jdbc.update(SEED_CAS, seed, now(), address, UNSEEDED);
                log.info("nonce 分配器播种 address={} seed={} 本方={}（0=并发对手已播种）",
                        address, seed, won);
                continue;
            }
            int bumped = jdbc.update(BUMP, now(), address);
            if (bumped != 1) {
                throw new NonceAllocationException(
                        "nonce 原子递增未命中行（水位仍处于不可分配状态）: address=" + address + ", next=" + next);
            }
            long afterBump = readNext(address);
            // 本事务刚 +1，读回的就是"下一次要发的值"；本次发放值 = 它减 1。
            // 另一副本此刻的 UPDATE 会被行锁挡到本事务提交，故不可能读到同一个值。
            return afterBump - 1;
        }
        throw new NonceAllocationException("nonce 认领在 " + MAX_ATTEMPTS + " 次尝试内未脱离播种态: address=" + address);
    }

    private Long readNext(String address) {
        return jdbc.query(READ_NEXT, rs -> rs.next() ? rs.getLong(1) : null, address);
    }

    private static Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    /** 分配失败（含 DB 故障与播种取值失败）。调用方必须把它当作"这笔不能发"，而不是退回旧的进程内计数。 */
    public static class NonceAllocationException extends RuntimeException {
        public NonceAllocationException(String message) {
            super(message);
        }

        public NonceAllocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
