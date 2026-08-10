package org.nexus.gateway.orchestration.connector;

public class ConnectorRefundResult {
    private boolean success;
    private String refundId;
    private String errorMessage;

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

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getRefundId() { return refundId; }
    public void setRefundId(String refundId) { this.refundId = refundId; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String msg) { this.errorMessage = msg; }
}