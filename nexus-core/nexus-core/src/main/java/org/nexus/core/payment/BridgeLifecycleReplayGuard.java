package org.nexus.core.payment;

import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨链桥生命周期重放防护（v2.2.0 安全修复）。
 *
 * <p>与 {@link BridgeMintReplayGuard}（BRIDGE_MINT 专用）并列，覆盖
 * {@code BRIDGE_LOCK} 与 {@code BRIDGE_BURN} 两个方向的幂等去重：</p>
 *
 * <ul>
 *   <li><b>BRIDGE_LOCK</b>：同一锁定意图（from 公钥 + targetChain + recipient + amount）
 *       只允许入账一次。此前 {@code PaymentTransactionProcessor} 以交易哈希记录锁单，
 *       同一 payload 以不同 nonce 重发（tx 哈希不同）会重复创建 LOCKED 记录；</li>
 *   <li><b>BRIDGE_BURN</b>：同一销毁意图（from + to + amount）只允许销毁一次，
 *       防止重复提交 burn 交易导致的重复结算。</li>
 * </ul>
 *
 * <p>幂等键使用 SHA-256 域分隔哈希（{@code NEXUS-BRIDGE-TRANSFER-v1}），
 * LOCK/MINT/BURN 三阶段共享同一语义键，防止跨方向、跨消息类型碰撞。</p>
 *
 * <p>线程安全：基于 {@link ConcurrentHashMap} + {@link AtomicLong}，支持并发区块处理。
 * 节点内存态实现，生产部署应随 PaymentStateStore 落库持久化。</p>
 */
@Component
public class BridgeLifecycleReplayGuard {

    private static final Logger log = LoggerFactory.getLogger(BridgeLifecycleReplayGuard.class);

    /** 锁定操作方向标识。 */
    public static final String KIND_LOCK = "LOCK";
    /** 铸造操作方向标识（仅用于统计，实际主键统一由语义键承载）。 */
    public static final String KIND_MINT = "MINT";
    /** 销毁操作方向标识。 */
    public static final String KIND_BURN = "BURN";

    /** BRIDGE_LOCK 幂等键域分隔前缀。 */
    private static final String LOCK_DOMAIN = "NEXUS-BRIDGE-LOCK-v1";
    /** BRIDGE_BURN 幂等键域分隔前缀。 */
    private static final String BURN_DOMAIN = "NEXUS-BRIDGE-BURN-v1";

    /** 各方向已消费幂等键集合（小写 hex）。 */
    private final Map<String, Set<String>> consumedByKind = new ConcurrentHashMap<>();

    /** 各方向被拒绝的重放次数统计（监控/测试用）。 */
    private final Map<String, AtomicLong> rejectedByKind = new ConcurrentHashMap<>();

    /**
     * 判断某方向的幂等键是否已被消费。
     *
     * @param kind   方向（{@link #KIND_LOCK} / {@link #KIND_BURN}）
     * @param keyHex 规范化幂等键的小写 hex
     * @return true 表示已消费，禁止再次执行
     */
    public boolean isConsumed(String kind, String keyHex) {
        if (keyHex == null || keyHex.isEmpty()) {
            return true; // 空键一律视为不可消费（fail-closed）
        }
        Set<String> set = consumedByKind.get(normalize(kind));
        return set != null && set.contains(keyHex);
    }

    /**
     * 标记某方向的幂等键已消费。重复标记为幂等操作。
     *
     * @param kind   方向（{@link #KIND_LOCK} / {@link #KIND_BURN}）
     * @param keyHex 幂等键小写 hex
     * @return true 表示本次调用完成首次标记，false 表示此前已标记（重放）
     */
    public boolean markConsumed(String kind, String keyHex) {
        if (keyHex == null || keyHex.isEmpty()) {
            rejectedByKind.computeIfAbsent(normalize(kind), k -> new AtomicLong()).incrementAndGet();
            return false;
        }
        String k = normalize(kind);
        Set<String> set = consumedByKind.computeIfAbsent(k, n -> ConcurrentHashMap.newKeySet());
        boolean first = set.add(keyHex);
        if (!first) {
            rejectedByKind.computeIfAbsent(k, n -> new AtomicLong()).incrementAndGet();
            log.warn("BRIDGE replay guard: replay rejected kind={} key={}", k, keyHex);
        } else {
            log.info("BRIDGE replay guard: marked consumed kind={} key={}", k, keyHex);
        }
        return first;
    }

    /**
     * 当前已消费条目数（监控/测试用）。
     *
     * @param kind 方向（{@link #KIND_LOCK} / {@link #KIND_BURN}）
     */
    public int size(String kind) {
        Set<String> set = consumedByKind.get(normalize(kind));
        return set != null ? set.size() : 0;
    }

    /**
     * 当前被拒绝的重放次数（监控/测试用）。
     *
     * @param kind 方向（{@link #KIND_LOCK} / {@link #KIND_BURN}）
     */
    public long rejected(String kind) {
        AtomicLong counter = rejectedByKind.get(normalize(kind));
        return counter != null ? counter.get() : 0L;
    }

    /**
     * 记录一次重放拒绝（不改变已消费集合，仅累加监控计数）。
     *
     * <p>处理器在 isConsumed 命中或状态机拒绝路径可直接调用，使
     * {@link #stats()} 中 {@code rejected*} 指标真实反映运行时拦截次数。</p>
     *
     * @param kind 方向（{@link #KIND_LOCK} / {@link #KIND_BURN}）
     */
    public void recordRejected(String kind) {
        rejectedByKind.computeIfAbsent(normalize(kind), k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * 全部方向的统计快照（监控暴露用）。
     *
     * @return 不可修改的统计 Map
     */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("consumedLock", size(KIND_LOCK));
        stats.put("consumedBurn", size(KIND_BURN));
        stats.put("rejectedLock", rejected(KIND_LOCK));
        stats.put("rejectedBurn", rejected(KIND_BURN));
        stats.put("rejectedTotal", rejected(KIND_LOCK) + rejected(KIND_BURN));
        return Collections.unmodifiableMap(stats);
    }

    /**
     * 规范化方向标识（null/空串回退为 LOCK，其余转大写）。
     */
    private static String normalize(String kind) {
        return (kind == null || kind.isEmpty()) ? KIND_LOCK : kind.toUpperCase();
    }

    /** 生命周期统一语义键域前缀（LOCK/MINT/BURN 共享，桥交易生命周期主干）。 */
    private static final String SEMANTIC_DOMAIN = "NEXUS-BRIDGE-TRANSFER-v1";

    /**
     * 计算跨链转账全生命周期幂等键（统一语义键）。
     *
     * <p>BRIDGE_LOCK → BRIDGE_MINT → BRIDGE_BURN 三阶段共享同一语义键：
     * 绑定锁定意图的全部语义字段（from 源链锁方公钥 + targetChain + recipient + amount），
     * 使同一笔跨链转账无论 nonce/txHash 如何变化都收敛到同一桥交易记录 ID，
     * 从而 mint 能以该键写入/读取记录，burn 也能以该键查询到对应 MINTED 记录。</p>
     *
     * @param fromHex     锁定/销毁发起方公钥 hex
     * @param targetChain 目标链标识
     * @param recipient   收款人地址
     * @param amount      跨链金额
     * @return SHA-256 域分隔哈希小写 hex（64 字符）
     */
    public static String computeSemanticKey(String fromHex, String targetChain, String recipient, long amount) {
        String data = SEMANTIC_DOMAIN + '\0'
                + (fromHex == null ? "" : fromHex) + '\0'
                + (targetChain == null ? "" : targetChain) + '\0'
                + (recipient == null ? "" : recipient) + '\0'
                + Long.toString(amount);
        return sha256Hex(data);
    }

    /**
     * 计算 BRIDGE_LOCK 幂等键：绑定锁定意图的全部语义字段，
     * 使同一锁定请求（无论 nonce/txHash）收敛到同一键。
     *
     * <p>保留方法维持旧调用兼容，等价于 {@link #computeSemanticKey}。</p>
     *
     * @param fromHex     锁定发起方公钥 hex
     * @param targetChain 目标链标识
     * @param recipient   收款人地址
     * @param amount      锁定金额
     * @return SHA-256 域分隔哈希小写 hex（64 字符）
     */
    public static String computeLockKey(String fromHex, String targetChain, String recipient, long amount) {
        return computeSemanticKey(fromHex, targetChain, recipient, amount);
    }

    /**
     * 计算 BRIDGE_BURN 幂等键：绑定销毁指令（from → targetChain + recipient + amount）。
     *
     * <p>v2.2.1 语义修正：burn 的幂等键与 lock/mint 共享同一语义键
     * （from + targetChain + recipient + amount），持同一桥交易记录。</p>
     *
     * <p>参数语义：burn 交易本身不直接携带 targetChain，但销毁指令的业务含义是
     * \"在本目标链上销毁此前锁定的资产\"，因此 targetChain 与 lock 阶段必须一致才能
     * 命中同一生命周期记录。toHex 形参参与键派生（兼容旧调用输入），
     * 实际语义比对以语义键为准。</p>
     *
     * @param fromHex     销毁发起方公钥 hex
     * @param targetChain 目标链标识（须与 lock 阶段一致才能命中同一记录）
     * @param toHex       收款方公钥哈希 hex（作为 recipient 兼容输入，参与键派生）
     * @param amount      销毁金额
     * @return SHA-256 域分隔哈希小写 hex（64 字符）
     */
    public static String computeBurnKey(String fromHex, String targetChain, String toHex, long amount) {
        return computeSemanticKey(fromHex, targetChain, toHex, amount);
    }

    /**
     * 兼容旧版两参签名（from + to + amount，targetChain 空串）。
     *
     * <p>保留以维持旧测试/旧调用编译兼容，但业务代码已改用三参版本；
     * 两参版本因 targetChain 为空串，与 lock/mint 的语义键不一致，仅测试用。</p>
     */
    public static String computeBurnKey(String fromHex, String toHex, long amount) {
        return computeBurnKey(fromHex, "", toHex, amount);
    }

    /**
     * 从持久化桥交易记录重建已消费集合（进程重启后的重放防护恢复）。
     *
     * <p>桥交易记录以语义键为 ID 落库；此方法将已进入终态（MINTED/BURNED/UNLOCKED）
     * 的记录映射到对应方向并标记为已消费，使重启后同一重放交易仍被拒绝。</p>
     * <p>非终态（PENDING/LOCKED/VALIDATING/FAILED/EXPIRED）不标记——
     * 这些状态不代表幂等完成，重启后允许重试。</p>
     *
     * @param storeTxs 当前 store 中所有桥交易记录
     */
    public void hydrateFrom(Collection<BridgeTransaction> storeTxs) {
        if (storeTxs == null) {
            return;
        }
        int restored = 0;
        for (BridgeTransaction tx : storeTxs) {
            String txId = tx != null ? tx.getBridgeTxId() : null;
            BridgeTransaction.State s = tx != null ? tx.getState() : null;
            if (txId == null || txId.isEmpty() || s == null) {
                continue;
            }
            if (s == BridgeTransaction.State.MINTED || s == BridgeTransaction.State.BURNED
                    || s == BridgeTransaction.State.UNLOCKED) {
                // LOCK 语义键与 BURN 语义键相同，两者都标记已消费并隔离消费状态
                Set<String> lockSet = consumedByKind.computeIfAbsent(KIND_LOCK, k -> ConcurrentHashMap.newKeySet());
                Set<String> burnSet = consumedByKind.computeIfAbsent(KIND_BURN, k -> ConcurrentHashMap.newKeySet());
                boolean firstLock = lockSet.add(txId);
                boolean firstBurn = burnSet.add(txId);
                if (firstLock || firstBurn) {
                    restored++;
                }
            }
        }
        if (restored > 0) {
            log.info("BRIDGE replay guard: hydrated {} bridge lifecycle keys from persistent store", restored);
        }
    }

    private static String sha256Hex(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Hex.encodeHexString(digest.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}