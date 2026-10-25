package org.nexus.db;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 区块写库失败聚合统计（PLAN-039 / roadmap #29 ①）。
 *
 * <p>背景：{@code StateDB.writeBlock} 在落库失败时按既有语义"记录日志 + 跳过该块
 * （等待后续确认轮次补写）"，但失败**只出现在日志里**——运维无法回答
 * "有没有写入尝试失败、失败了多少次、最后一次在高度多少"。本类把失败聚合成
 * 内存计数，经 {@code GET /consensus/block-writer} 观测（不依赖数据库，
 * PG 故障期间仍可读）。</p>
 *
 * <p><b>计数口径</b>：{@code writeFailures} 是**写入失败尝试次数**，不是不同区块数——
 * 同一高度在后续确认轮次被反复尝试时会重复计数（测试床实测 2 分钟 1666 次
 * 对应约 50 个高度区间）。{@code lastFailureHeight} 是最近一次失败尝试的高度。
 * 计数只增不减（进程生命周期内累计）；进程重启清零——与"失败聚合/告警"的
 * 用途一致（不是持久化审计）。</p>
 *
 * @since 2.76.0
 */
public class BlockWriteStats {

    /** 落库失败/跳块的尝试次数（同一高度重试会重复计数；后续确认轮次会补写）。 */
    private final AtomicLong writeFailures = new AtomicLong();

    /** 账户状态更新等待超时次数（pendingBlock 被强制清空，防死锁）。 */
    private final AtomicLong stateUpdateTimeouts = new AtomicLong();

    /** 最近一次写入异常（跳块或状态更新超时）的高度（无异常为 -1）。 */
    private final AtomicLong lastFailureHeight = new AtomicLong(-1L);

    /** 最近一次写入异常的时刻（epoch millis；无异常为 0）。 */
    private final AtomicLong lastFailureAtMillis = new AtomicLong(0L);

    /**
     * 记录一次落库失败（区块被跳过）。同一高度在后续确认轮次重试会再次计数。
     *
     * @param height 失败区块高度
     */
    public void recordWriteFailure(long height) {
        writeFailures.incrementAndGet();
        lastFailureHeight.set(height);
        lastFailureAtMillis.set(System.currentTimeMillis());
    }

    /**
     * 记录一次状态更新等待超时（同时更新最近异常高度/时刻）。
     *
     * @param height 超时时挂在 pendingBlock 的区块高度
     */
    public void recordStateUpdateTimeout(long height) {
        stateUpdateTimeouts.incrementAndGet();
        lastFailureHeight.set(height);
        lastFailureAtMillis.set(System.currentTimeMillis());
    }

    public long writeFailures() {
        return writeFailures.get();
    }

    public long stateUpdateTimeouts() {
        return stateUpdateTimeouts.get();
    }

    /**
     * 最近一次写入异常（跳块或状态更新超时）的高度（无异常为 -1）。
     *
     * @return 高度；-1 表示进程启动以来尚未记录异常
     */
    public long lastFailureHeight() {
        return lastFailureHeight.get();
    }

    /**
     * 最近一次写入异常的时刻（epoch millis；无异常为 0）。
     *
     * @return epoch millis；0 表示尚未记录异常
     */
    public long lastFailureAtMillis() {
        return lastFailureAtMillis.get();
    }

    /**
     * 只读快照（供观测端点）。
     *
     * @return 字段：writeFailures / stateUpdateTimeouts / lastFailureHeight（最新异常高度）/
     *         lastFailureAtMillis（最新异常时刻）
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("writeFailures", writeFailures.get());
        data.put("stateUpdateTimeouts", stateUpdateTimeouts.get());
        data.put("lastFailureHeight", lastFailureHeight.get());
        data.put("lastFailureAtMillis", lastFailureAtMillis.get());
        return data;
    }
}
