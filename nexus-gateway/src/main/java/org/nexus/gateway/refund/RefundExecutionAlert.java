package org.nexus.gateway.refund;

/**
 * 退款执行链路的结构化告警标记 — 供日志/监控精确匹配（与
 * {@code org.nexus.gateway.transaction.RecoveryAlert} 同款约定）。
 *
 * <p>当 {@link DefaultRefundApprovalService#executeRefund} 认领（APPROVED → EXECUTING）
 * 之后、链上出款与实际结果落库之间发生崩溃/落库失败时，退款单会停留在 EXECUTING：
 * 链上是否已出款在这一刻是<b>未知</b>的。{@code RefundExecutionReconciler} 扫描到超时
 * 未决的单据后只打 WARN + 审计，<b>不自动改状态、不自动重试</b>：</p>
 * <ul>
 *   <li>自动改 FAILED —— 若链上实际已出款，是把"已打款"标成"失败"（假阴性）；</li>
 *   <li>自动改 EXECUTED —— 若链上实际未出款，是把"没打款"标成"已成功"（假阳性）；</li>
 *   <li>自动重试出款 —— 若首次实际已出款，则会二次打款。</li>
 * </ul>
 *
 * <p>安全取向与「宁可显式挂起 + 告警，不可静默自动处置」同源：出现本标记即表示
 * 有退款处于"已认领、结果未知"状态，需人工核对链上交易后手动处置。</p>
 */
public final class RefundExecutionAlert {

    private RefundExecutionAlert() {
    }

    /**
     * 退款单超时停留在 EXECUTING 时写入 WARN 首字段的稳定标记。
     * 监控应据此告警：出现即表示有退款的最终结果未知，需人工核对链上是否已出款。
     */
    public static final String MANUAL_VERIFICATION_REQUIRED = "REFUND_EXECUTION_MANUAL_VERIFICATION_REQUIRED";
}
