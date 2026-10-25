package org.nexus.bridge.safety;

/**
 * 日限额用量存储（PLAN-038：roadmap #33 ②"日限额内存态"收口）。
 *
 * <p>语义：滚动 24 小时窗口内的"已用量"，为 {@code BridgeServiceImpl} 的
 * 日限额配额预留/归还提供存储。写操作（预留/归还）必须<b>立即提交、独立于
 * 调用方业务事务</b>——这是既有 in-memory 实现的行为契约（配额先占，业务
 * 回滚时由调用方 afterCompletion 补偿归还）；Jdbc 实现通过 REQUIRES_NEW
 * 忠实复刻。</p>
 *
 * <p>窗口语义（与历史实现逐条一致）：窗口起点随"过窗后的首次预留"滚动；
 * 过窗后读为 0；纯读操作<b>不</b>落库滚动（由下次写操作落库）。</p>
 *
 * @since 2.76.0
 */
public interface DailyUsageStore {

    /** 滚动窗口长度（24 小时，与历史 lazy 重置语义一致）。 */
    long WINDOW_MILLIS = 86_400_000L;

    /**
     * 读取当前窗口已用量（过窗读为 0；纯读，不写库）。
     *
     * @param nowMillis 当前时刻（epoch millis，显式传入便于确定性测试）
     * @return 当前窗口已用量
     */
    long currentUsed(long nowMillis);

    /**
     * 尝试在滚动窗口内预留配额（原子；立即生效、独立于调用方事务）。
     *
     * @param amount    预留金额（NEX 最小单位）
     * @param limit     日限额上限
     * @param nowMillis 当前时刻（epoch millis）
     * @return 预留成功返回 true；若 {@code 当前已用 + amount > limit} 返回 false（不产生预留）
     */
    boolean tryReserve(long amount, long limit, long nowMillis);

    /**
     * 归还此前预留的配额（补偿；立即生效、独立于调用方事务；下限 0）。
     *
     * <p>若当前窗口已过窗，本次归还为空操作（旧窗口余量已随滚动作废）。</p>
     *
     * @param amount    归还金额
     * @param nowMillis 当前时刻（epoch millis）
     */
    void release(long amount, long nowMillis);
}
