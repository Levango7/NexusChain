package org.nexus.gateway.refund;

/**
 * 退款出款"已提交、结果未知"（P0 修复 2026-10-21）。
 *
 * <p><b>为什么必须与普通失败区分</b>：链上出款是不可逆外部调用。若交易已广播但
 * 未达到确认数（{@code TransactionResult.Status.PENDING_CONFIRMATION}），
 * 或签名/广播调用抛出无法判定是否已提交的异常，则"钱是否已出"是<b>未知</b>。</p>
 *
 * <p>把"未知"当成"确定未出款"会落到 {@code FAILED}——而 {@code FAILED} 在本系统的
 * 状态机里是<b>终态且无出路</b>：{@code claimForExecution} 只认 APPROVED、
 * {@code approveRefund} 只认 PENDING、{@code RefundExecutionReconciler} 只扫 EXECUTING。
 * 结果是钱已出、记录失败、且没有任何自动或人工路径会发现。</p>
 *
 * <p><b>正确处置</b>：保持单据在 {@code EXECUTING}，由
 * {@link RefundExecutionReconciler} 超时告警后人工核对。既不落 FAILED（假阴性），
 * 也不自动重试出款（二次打款）。</p>
 */
public class RefundSubmitOutcomeUnknownException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RefundSubmitOutcomeUnknownException(String message) {
        super(message);
    }

    public RefundSubmitOutcomeUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
