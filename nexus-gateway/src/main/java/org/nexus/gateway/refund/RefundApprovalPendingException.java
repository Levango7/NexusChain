package org.nexus.gateway.refund;

/**
 * 退款被大额签名审批拦下（{@code statusCode=2001}，尚未签名/广播）。
 *
 * <p><b>为什么不能当失败</b>：2001 的语义是"签名服务已受理、等待人工审批"，
 * 资金一分未动，但也不是完成。旧实现把"取不到 txHash"当签名失败，直接落
 * {@code FAILED}，审批通过后也没有任何路径能把退款推进到执行——退款永久丢失。</p>
 *
 * <p><b>正确处置</b>：单据置 {@code AWAITING_APPROVAL}（非终态），审批通过后由
 * {@link RefundApprovalExecutionReconciler} 重新认领并执行；审批拒绝/过期则转
 * {@code FAILED}。等待期间该笔仍计入退款额度，防止审批窗口内重复超退。</p>
 */
public class RefundApprovalPendingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** signing-service 的审批请求 ID（排障用）。 */
    private final String approvalId;

    public RefundApprovalPendingException(String approvalId) {
        super("refund requires signing approval: approvalId=" + approvalId);
        this.approvalId = approvalId;
    }

    public String getApprovalId() {
        return approvalId;
    }
}
