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

    /**
     * 建行拆成两条单表 DML：Seata AT 的数据源代理解析不了
     * {@code INSERT ... SELECT ... WHERE NOT EXISTS}（抛 NotSupportYetException
     * "not support the sql syntax insert with query"），全局事务里首次签名会直接 500。
     * 语义不变：先探存在性（UPDATE 命中即已存在），不存在才插，并发由主键兜。
     */
    private static final String TOUCH_ROW =
            "UPDATE nonce_allocation SET updated_at = ? WHERE address = ?";

    private static final String INSERT_ROW =
            "INSERT INTO nonce_allocation (address, next_nonce, updated_at) VALUES (?, ?, ?)";

    private static final String SEED_CAS =
            "UPDATE nonce_allocation SET next_nonce = ?, updated_at = ?, last_reconciled_at = ? "
                    + "WHERE address = ? AND next_nonce = ?";

    private static final String BUMP =
            "UPDATE nonce_allocation SET next_nonce = next_nonce + 1, updated_at = ? "
                    + "WHERE address = ? AND next_nonce >= 0";

    private static final String READ_NEXT =
            "SELECT next_nonce FROM nonce_allocation WHERE address = ?";

    /**
     * 校正租约：把"上次校正时间"从到期翻成未到期，**只有翻成功的那个副本**去打 RPC。
     * {@code next_nonce >= 0} 保证未播种的行归播种路径管，两条路径不抢同一行。
     */
    private static final String ACQUIRE_RECONCILE_LEASE =
            "UPDATE nonce_allocation SET last_reconciled_at = ? "
                    + "WHERE address = ? AND next_nonce >= 0 "
                    + "AND (last_reconciled_at IS NULL OR last_reconciled_at <= ?)";

    /** 向前校正：只在链上「下一个可用值」比本地水位更靠时时生效，天然不可能把水位调回。 */
    private static final String BUMP_FORWARD =
            "UPDATE nonce_allocation SET next_nonce = ?, updated_at = ? "
                    + "WHERE address = ? AND next_nonce < ?";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    /** 水位校正间隔（秒）；{@code <= 0} 表示关闭周期性校正（仍保留首次播种）。 */
    private final long reconcileIntervalSeconds;

    public NonceAllocator(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this(jdbc, transactionManager, 300L);
    }

    /**
     * @param reconcileIntervalSeconds 全局限流：每个地址最多每这么多秒实测一次链上 nonce。
     *                                 0/负数 = 关闭周期校正（避免每次认领都打链，把省掉的 RPC 开销加回来）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public NonceAllocator(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${nexus.signing.nonce.reconcile-interval-seconds:300}") long reconcileIntervalSeconds) {
        this.jdbc = jdbc;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        this.reconcileIntervalSeconds = reconcileIntervalSeconds;
    }

    /**
     * 原子认领该地址的下一个 nonce。
     *
     * @param address   发送方地址（本仓所有转账共用唯一平台地址，见 {@code TxController} 的
     *                  {@code pubkey_mismatch} 校验）
     * @param chainSeed 该地址<b>上一笔已应用</b>的 nonce（节点 {@code account.nonce} 语义）——
     *                  仅在该地址首次认领（哨兵态）时调用一次，平时不产生 RPC 开销。
     *                  分配器按节点规则以 {@code chainSeed + 1} 作为水位起点（见 {@code doClaim} 内注释）。
     *                  取值失败应直接抛异常。
     * @return 本次认领到的 nonce（调用方是唯一持有者，可安全用于签名广播）
     * @throws NonceAllocationException DB 不可用、行无法递增或读到非法水位
     */
    public long claim(String address, LongSupplier chainSeed) {
        ensureRow(address);
        maybeReconcileForward(address, chainSeed);
        Long allocated = txTemplate.execute(status -> doClaim(address, chainSeed));
        if (allocated == null) {
            throw new NonceAllocationException("nonce 认领返回空事务结果: address=" + address);
        }
        return allocated;
    }

    /** 建行独立成一步（不在认领事务里），让"并发首次建行"的主键冲突只影响这一条幂等 INSERT。 */
    private void ensureRow(String address) {
        if (jdbc.update(TOUCH_ROW, now(), address) > 0) {
            return;
        }
        try {
            jdbc.update(INSERT_ROW, address, UNSEEDED, now());
        } catch (org.springframework.dao.DuplicateKeyException concurrentFirstCreator) {
            // 并发首次建行的正常结果：主键挡住了一方，行已经存在，直接走后面的认领路径。
            log.debug("nonce_allocation 行由并发方先建: address={}", address);
        }
    }

    /**
     * 水位向前校正（#48 第二半）。分三段、**不在 RPC 期间持有行锁**：
     * <ol>
     *   <li>短事务抢租约（条件 UPDATE）。抢不到 = 别的副本正在/刚刚校过 ⇒ 直接跳过，零 RPC。</li>
     *   <li>事务外读链上一次已应用值（唯一打 RPC 的地方，每地址每 {@code reconcileIntervalSeconds} 至多一次）。</li>
     *   <li>短事务向前推水位：{@code WHERE next_nonce < chain+1} ⇒ 只前进不后退
     *       （水位高于链是正常状态：有在飞交易未上链）。</li>
     * </ol>
     *
     * <p>RPC 失败时<b>不</b>阻断发放，也不回滚租约：留着租约到下一个周期再试，比"每次发放都打链"
     * 或"发放挂掉"都更可取；此时若外部确实消耗过 nonce，症状是交易被节点丢弃，
     * 由调用方的确认扫描（PoolTask）暴露，而不是在这里静默猜。</p>
     */
    private void maybeReconcileForward(String address, LongSupplier chainSeed) {
        if (reconcileIntervalSeconds <= 0) {
            return;
        }
        Timestamp now = now();
        Timestamp cutoff = Timestamp.from(now.toInstant().minusSeconds(reconcileIntervalSeconds));
        Integer won = txTemplate.execute(status ->
                jdbc.update(ACQUIRE_RECONCILE_LEASE, now, address, cutoff));
        if (won == null || won == 0) {
            return;
        }
        long chainValue;
        try {
            chainValue = chainSeed.getAsLong();
        } catch (RuntimeException rpcFailed) {
            log.warn("nonce 水位校正取链上值失败，本周期跳过（租约已消耗，下个周期再试）: address={}, 原因={}",
                    address, rpcFailed.toString());
            return;
        }
        long nextUsable = Math.addExact(chainValue, 1L);
        Integer moved = txTemplate.execute(status ->
                jdbc.update(BUMP_FORWARD, nextUsable, now, address, nextUsable));
        if (moved != null && moved > 0) {
            log.warn("nonce 水位检测到分配器之外的消耗，已向前校正: address={} 原水位<{} ⇒ 抬到 {}"
                            + "（链上上一笔已应用={}）",
                    address, nextUsable, nextUsable, chainValue);
        }
    }

    private Long doClaim(String address, LongSupplier chainSeed) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Long next = readNext(address);
            if (next == null) {
                throw new NonceAllocationException("nonce_allocation 行缺失: address=" + address);
            }
            if (next == UNSEEDED) {
                long chainValue;
                try {
                    chainValue = chainSeed.getAsLong();
                } catch (RuntimeException seedFailed) {
                    // 契约：发放失败一律以 NonceAllocationException 表达。调用方只需 catch 这一个
                    // 类型就能保证"要么拿到唯一 nonce，要么这笔不发"——不必关心失败源自 RPC 还是 DB。
                    throw new NonceAllocationException(
                            "播种链上 nonce 失败，拒绝发放: address=" + address + ", 原因=" + seedFailed, seedFailed);
                }
                // 节点语义（现查，非推测）：
                //   nexus-core/…/Impl/HatchServiceImpl.java:91-97  getNonce 取 best block 上的 account.nonce
                //   nexus-core/…/core/validate/MerkleRule.java:380/420/431/455  应用交易时 setNonce(tran.nonce)
                //     ⇒ 账户里存的是**上一笔已应用**的 nonce，不是"下一个可用"。
                //   nexus-core/…/consensus/pow/PackageMiner.java:74  `account.nonce >= tx.nonce` 即丢弃
                //     ⇒ 合法条件严格是 tx.nonce > account.nonce。
                // 所以水位起点必须是 chain+1。旧实现（TxController 池空分支 / TCC 同形分支）直接拿
                // chain 当下一个号，节点会把它当"重复 nonce"丢弃，而调用方已收到 statusCode=2000 —
                // 这是被 #33 从"每次冷启动后第一笔"收敛到"每地址首次"的存量 off-by-one，在此一并修。
                long nextUsable = Math.addExact(chainValue, 1L);
                int won = jdbc.update(SEED_CAS, nextUsable, now(), now(), address, UNSEEDED);
                log.info("nonce 分配器播种 address={} 链上上一笔已应用={} ⇒ 水位起点={}（0=并发对手已播种）",
                        address, chainValue, nextUsable, won);
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
