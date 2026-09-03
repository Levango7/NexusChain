package org.nexus.gateway.orchestration.connector;

/**
 * Result returned by a connector after creating a payment.
 *
 * <p><b>可观测字段</b>：{@link #latencyMs} 与 {@link #costBps} 由 connector 在
 * 内部测量后回填（典型来源：调用 RPC/PSP 的端到端耗时、connector 的费率基点）。
 * 调用方（如 {@code OrchestrationService}）若发现字段为 0，可作为兜底用外层
 * 计时填充；多数场景下 connector 已经报告了真实值。</p>
 */
public class ConnectorPaymentResult {
    private boolean success;
    private String connectorPaymentId;
    private PaymentStatus status;
    private String transactionHash;
    private String errorMessage;
    private String redirectUrl;
    /** 本次支付端到端耗时（毫秒），0 表示未测量或 connector 未回填。 */
    private long latencyMs;
    /** 本次支付成本（basis points），0 表示 connector 未回填（fallback: connector.feeBasisPoints()）。 */
    private int costBps;

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

    /**
     * 链式填充延迟（毫秒）。Connector 内部计时场景使用。
     */
    public ConnectorPaymentResult withLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
        return this;
    }

    /**
     * 链式填充成本（basis points）。Connector 内部已知费率时使用。
     */
    public ConnectorPaymentResult withCostBps(int costBps) {
        this.costBps = costBps;
        return this;
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
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public int getCostBps() { return costBps; }
    public void setCostBps(int costBps) { this.costBps = costBps; }
}
