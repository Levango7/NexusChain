package org.nexus.gateway.orchestration.connector;

public class ConnectorRefundResult {
    private boolean success;
    private String refundId;
    private String errorMessage;
    /**
     * 大额退款转人工审批（或执行结果待人工核对）：钱未出账，但既不是成功也不是失败。
     * 调用方不得据此判定退款失败——审批通过后退款仍会由执行腿完成。
     */
    private boolean pending;
    /** 审批请求 ID（仅 {@link #pending} 为 true 时非空），用于跨系统跟踪同一条审批。 */
    private String approvalId;

    public static ConnectorRefundResult ok(String refundId) {
        ConnectorRefundResult r = new ConnectorRefundResult();
        r.success = true;
        r.refundId = refundId;
        return r;
    }

    public static ConnectorRefundResult fail(String msg) {
        ConnectorRefundResult r = new ConnectorRefundResult();
        r.success = false;
        r.errorMessage = msg;
        return r;
    }

    /** 待人工审批（或待人工核对）：既非成功也非失败。 */
    public static ConnectorRefundResult pending(String refundId, String approvalId) {
        ConnectorRefundResult r = new ConnectorRefundResult();
        r.success = false;
        r.pending = true;
        r.refundId = refundId;
        r.approvalId = approvalId;
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public boolean isPending() { return pending; }
    public void setPending(boolean pending) { this.pending = pending; }
    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }
    public String getRefundId() { return refundId; }
    public void setRefundId(String refundId) { this.refundId = refundId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String msg) { this.errorMessage = msg; }
}