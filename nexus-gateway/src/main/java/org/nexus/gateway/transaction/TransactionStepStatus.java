package org.nexus.gateway.transaction;

/**
 * 分布式事务步骤状态枚举。
 *
 * <p>覆盖 TCC 和 SAGA 两种事务模式下所有可能的步骤状态：</p>
 * <ul>
 *   <li>{@link #TRY} — TCC Try 阶段（资源预留）</li>
 *   <li>{@link #CONFIRM} — TCC Confirm 阶段（确认提交）</li>
 *   <li>{@link #CANCEL} — TCC Cancel 阶段（取消预留）</li>
 *   <li>{@link #COMPENSATE} — SAGA 补偿阶段（逆向补偿已完成步骤）</li>
 *   <li>{@link #PENDING} — 事务已创建但尚未开始执行</li>
 *   <li>{@link #SUCCESS} — 步骤/事务执行成功（终态）</li>
 *   <li>{@link #FAILED} — 步骤/事务执行失败（终态）</li>
 *   <li>{@link #PENDING_MANUAL} — 恢复动作无法自动完成（如进程重启后 action/steps 丢失），
 *       已显式移交人工确认资源是否释放；非终态成功、且被超时扫描排除（不每 60s 反复重试）</li>
 * </ul>
 */
public enum TransactionStepStatus {
    /** TCC Try 阶段：资源预留 */
    TRY,
    /** TCC Confirm 阶段：确认提交 */
    CONFIRM,
    /** TCC Cancel 阶段：取消预留 */
    CANCEL,
    /** SAGA 补偿阶段：逆向补偿 */
    COMPENSATE,
    /** 事务已创建，尚未开始执行 */
    PENDING,
    /** 步骤/事务执行成功（终态） */
    SUCCESS,
    /** 步骤/事务执行失败（终态） */
    FAILED,
    /**
     * 恢复动作无法自动完成，已移交人工确认（需人工跟进）。
     *
     * <p>仅由恢复调度器链路（{@code retryConfirm}/{@code retryCancel}/{@code retryCompensate}）
     * 在<b>进程内注册表 miss</b>（典型为服务重启后 {@code actionRegistry}/{@code stepRegistry} 空）时写入：
     * 既不能自动释放预留资源、又不该谎报为 {@link #FAILED} 假装已处理，故显式挂起 + 告警。</p>
     *
     * <p>超时扫描只查 TRY/CONFIRM/PENDING/COMPENSATE，天然不会重捞 {@code PENDING_MANUAL} ⇒
     * 已交人工的项不会被每 60 秒反复重试。列宽 VARCHAR(32) 足够，无需 Flyway 迁移。</p>
     */
    PENDING_MANUAL
}