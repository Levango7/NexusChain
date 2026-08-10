package org.nexus.l2.sequencer;

import org.nexus.l2.L2Transaction;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;

/**
 * L2 交易排序策略（nonce + 优先费）。
 *
 * <p>排序规则（按优先级递减）：</p>
 * <ol>
 *   <li><b>账户地址升序</b>：相同账户的交易聚集，便于按 nonce 顺序处理</li>
 *   <li><b>账户 nonce 升序</b>：同一账户内严格按 nonce 顺序，避免 nonce 乱序导致后续 tx 无效</li>
 *   <li><b>优先费降序</b>：跨账户时高优先费交易优先打包，最大化排序器收益</li>
 *   <li><b>txHash 字典序</b>：确定性 tie-breaker，保证排序结果稳定可复现</li>
 * </ol>
 *
 * <p>设计动机：</p>
 * <ul>
 *   <li>按 nonce 升序保证账户内交易连续性，避免因前序 tx 缺失导致后续 tx 执行失败</li>
 *   <li>优先费降序激励用户付费优先打包，同时不破坏账户内 nonce 顺序</li>
 *   <li>账户地址作为主键聚集同账户 tx，使 nonce 排序在账户内有效</li>
 * </ul>
 *
 * @since 1.3
 */
public final class SequencingPolicy {

    /** 单例默认策略 */
    private static final SequencingPolicy DEFAULT = new SequencingPolicy();

    /** 是否在排序时过滤掉 nonce 重复的 tx（保留首个） */
    private final boolean dedupNonce;

    /** null sender 排序时的占位字符串（保证 null 排在最后） */
    private static final String NULL_SENDER_SENTINEL = "\uffff\uffff\uffff";

    private SequencingPolicy() {
        this(false);
    }

    private SequencingPolicy(boolean dedupNonce) {
        this.dedupNonce = dedupNonce;
    }

    /**
     * 获取默认策略实例。
     *
     * @return 默认策略
     */
    public static SequencingPolicy defaultPolicy() {
        return DEFAULT;
    }

    /**
     * 创建启用 nonce 去重的策略。
     *
     * @param dedupNonce 是否去重同账户同 nonce 的 tx（保留首个）
     * @return 策略实例
     */
    public static SequencingPolicy withNonceDedup(boolean dedupNonce) {
        return new SequencingPolicy(dedupNonce);
    }

    /**
     * 对交易列表原地排序（nonce + 优先费策略）。
     *
     * @param txs 待排序交易列表；null 或空直接返回
     */
    public void sort(List<L2Transaction> txs) {
        if (txs == null || txs.size() <= 1) {
            return;
        }
        txs.sort(comparator());
        if (dedupNonce) {
            dedupByNonce(txs);
        }
    }

    /**
     * 构造排序比较器。
     *
     * @return 比较器
     */
    public Comparator<L2Transaction> comparator() {
        return Comparator
                // 1. 账户地址升序（null 排最后）
                .comparing(L2Transaction::getSender,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                // 2. 账户 nonce 升序
                .thenComparingLong(L2Transaction::getNonce)
                // 3. 优先费降序（高优先费在前）
                .thenComparing(this::comparePriorityFeeDescending)
                // 4. txHash 字典序（确定性 tie-breaker）
                .thenComparing(L2Transaction::getTxHash,
                        Comparator.nullsLast(Comparator.naturalOrder()));
    }

    /**
     * 优先费降序比较（null-safe，高优先费在前）。
     *
     * @param a 交易 a
     * @param b 交易 b
     * @return 比较结果（b.priorityFee 与 a.priorityFee 比较，实现降序）
     */
    private int comparePriorityFeeDescending(L2Transaction a, L2Transaction b) {
        BigInteger pa = a.getPriorityFee() == null ? BigInteger.ZERO : a.getPriorityFee();
        BigInteger pb = b.getPriorityFee() == null ? BigInteger.ZERO : b.getPriorityFee();
        return pb.compareTo(pa);
    }

    /**
     * 去重同账户同 nonce 的 tx（保留首个，移除后续）。
     *
     * @param txs 已按策略排序的交易列表
     */
    private void dedupByNonce(List<L2Transaction> txs) {
        String lastSender = null;
        long lastNonce = -1;
        java.util.Iterator<L2Transaction> it = txs.iterator();
        while (it.hasNext()) {
            L2Transaction tx = it.next();
            String sender = tx.getSender();
            long nonce = tx.getNonce();
            if (sender != null && sender.equals(lastSender) && nonce == lastNonce) {
                it.remove();
            } else {
                lastSender = sender;
                lastNonce = nonce;
            }
        }
    }

    /**
     * 检查交易列表是否满足账户内 nonce 严格递增约束。
     *
     * @param txs 交易列表
     * @return 满足约束返回 true
     */
    public boolean isNonceOrdered(List<L2Transaction> txs) {
        if (txs == null || txs.size() <= 1) {
            return true;
        }
        String lastSender = null;
        long lastNonce = -1;
        for (L2Transaction tx : txs) {
            String sender = tx.getSender();
            long nonce = tx.getNonce();
            if (sender != null) {
                if (sender.equals(lastSender) && nonce <= lastNonce) {
                    return false;
                }
                if (!sender.equals(lastSender)) {
                    lastSender = sender;
                }
                lastNonce = nonce;
            }
        }
        return true;
    }
}