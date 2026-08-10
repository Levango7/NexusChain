package org.nexus.signing.mpc.barrier;

import org.nexus.signing.mpc.MpcProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 轮次屏障：同步所有参与者完成同一轮次后再进入下一轮。
 *
 * <p>GG18/GG20 协议要求严格轮次同步：所有参与者必须完成第 k 轮（收到
 * 该轮所有期望消息）后才能进入第 k+1 轮。该屏障在每个轮次为每个参与者
 * 注册一个 {@code arrived} 标记，当且仅当 {@code threshold} 个参与者到达
 * 时唤醒所有等待者。</p>
 *
 * <p><b>使用方式</b>：</p>
 * <pre>
 *   RoundBarrier barrier = new RoundBarrier(sessionId, threshold);
 *   // 每个参与者完成本轮后：
 *   barrier.arrive(round, participantId);
 *   // 等待所有参与者完成本轮：
 *   barrier.awaitRound(round, timeoutMillis);
 *   // 进入下一轮
 * </pre>
 *
 * <p><b>线程安全</b>：内部用 {@link ReentrantLock} + {@link Condition} 实现，
 * 多个参与者线程可并发调用 {@link #arrive}。</p>
 */
public class RoundBarrier {

    private static final Logger log = LoggerFactory.getLogger(RoundBarrier.class);

    private final String sessionId;
    private final int threshold;

    /** round -> 已到达的参与者集合。 */
    private final ConcurrentHashMap<Integer, Set<String>> arrived = new ConcurrentHashMap<>();

    /** round -> 锁与条件。 */
    private final ConcurrentHashMap<Integer, RoundLock> locks = new ConcurrentHashMap<>();

    /**
     * 构造轮次屏障。
     *
     * @param sessionId 会话 ID
     * @param threshold 触发阈值（参与者数）
     */
    public RoundBarrier(String sessionId, int threshold) {
        if (threshold < 1) throw new IllegalArgumentException("threshold must be >= 1");
        this.sessionId = sessionId;
        this.threshold = threshold;
    }

    /**
     * 标记一个参与者在指定轮次到达。
     *
     * @param round          轮次号
     * @param participantId  参与者 ID
     */
    public void arrive(int round, String participantId) {
        Set<String> set = arrived.computeIfAbsent(round, k ->
                Collections.newSetFromMap(new ConcurrentHashMap<>()));
        boolean added = set.add(participantId);
        if (added) {
            log.debug("Barrier {}: participant {} arrived at round {} ({}/{})",
                    sessionId, participantId, round, set.size(), threshold);
        }
        if (set.size() >= threshold) {
            RoundLock rl = locks.computeIfAbsent(round, k -> new RoundLock());
            rl.signalAll();
        }
    }

    /**
     * 等待指定轮次的所有参与者到达。
     *
     * @param round          轮次号
     * @param timeoutMillis  超时（毫秒）
     * @throws MpcProtocolException 若超时（{@link MpcProtocolException.Reason#TIMEOUT}）
     */
    public void awaitRound(int round, long timeoutMillis) {
        Set<String> set = arrived.computeIfAbsent(round, k ->
                Collections.newSetFromMap(new ConcurrentHashMap<>()));
        if (set.size() >= threshold) return;

        RoundLock rl = locks.computeIfAbsent(round, k -> new RoundLock());
        long deadline = System.currentTimeMillis() + timeoutMillis;
        rl.lock.lock();
        try {
            while (set.size() < threshold) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    throw new MpcProtocolException(
                            MpcProtocolException.Reason.TIMEOUT,
                            "barrier timeout: session=" + sessionId + ", round=" + round
                                    + ", arrived=" + set.size() + "/" + threshold);
                }
                try {
                    rl.condition.await(remaining, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new MpcProtocolException(
                            MpcProtocolException.Reason.TIMEOUT,
                            "barrier interrupted", e);
                }
            }
        } finally {
            rl.lock.unlock();
        }
        log.debug("Barrier {}: round {} reached quorum {}/{}",
                sessionId, round, set.size(), threshold);
    }

    /**
     * @return 指定轮次已到达的参与者数
     */
    public int getArrivedCount(int round) {
        Set<String> set = arrived.get(round);
        return set == null ? 0 : set.size();
    }

    /**
     * 重置屏障（用于会话重连后重新开始）。
     */
    public void reset() {
        arrived.clear();
        locks.clear();
    }

    /**
     * 单轮锁与条件。
     */
    private static final class RoundLock {
        final Lock lock = new ReentrantLock();
        final Condition condition = lock.newCondition();

        void signalAll() {
            lock.lock();
            try {
                condition.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}