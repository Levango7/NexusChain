package org.nexus.gateway.orchestration.connector;

/**
 * Result returned by a connector after creating a payment.
 */
public class ConnectorPaymentResult {
    private boolean success;
    private String connectorPaymentId;
    private PaymentStatus status;
    private String transactionHash;
    private String errorMessage;
    private String redirectUrl;

    public static ConnectorPaymentResult ok(String connectorPaymentId, PaymentStatus status) {
        ConnectorPaymentResult r = new ConnectorPaymentResult();
        r.success = true;
        r.connectorPaymentId = connectorPaymentId;
        r.status = status;
        return r;
    }

    public static ConnectorPaymentResult ok(String connectorPaymentId, PaymentStatus status, String txHash) {
        ConnectorPaymentResult r = ok(connectorPaymentId, status);
        r.transactionHash = txHash;
        return r;
    }

    public static ConnectorPaymentResult fail(String errorMessage) {
        ConnectorPaymentResult r = new ConnectorPaymentResult();
        r.success = false;
        r.status = PaymentStatus.FAILED;
        r.errorMessage = errorMessage;
        return r;
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getConnectorPaymentId() { return connectorPaymentId; }
    public void setConnectorPaymentId(String id) { this.connectorPaymentId = id; }
    public PaymentStatus getStatus() { return status; }
    public void setStatus(PaymentStatus status) { this.status = status; }
    public String getTransactionHash() { return transactionHash; }
    public void setTransactionHash(String txHash) { this.transactionHash = txHash; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String msg) { this.errorMessage = msg; }
    public String getRedirectUrl() { return redirectUrl; }
    public void setRedirectUrl(String url) { this.redirectUrl = url; }
}