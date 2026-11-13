package org.nexus.gateway.orchestration.connector;

import org.nexus.gateway.model.FinalityStatus;
import org.nexus.gateway.model.SubmissionStatus;

/**
 * Result returned by a connector after creating a payment.
 *
 * <p><b>可观测字段</b>：{@link #latencyMs} 与 {@link #costBps} 由 connector 在
 * 内部测量后回填（典型来源：调用 RPC/PSP 的端到端耗时、connector 的费率基点）。
 * 调用方（如 {@code OrchestrationService}）若发现字段为 0，可作为兜底用外层
 * 计时填充；多数场景下 connector 已经报告了真实值。</p>
 *
 * <p><b>最终性字段</b>（Step 1 新增）：{@link #submissionStatus} 描述交易提交阶段，
 * {@link #finalityStatus} 描述最终性程度。{@code ok()} 工厂方法默认设置
 * {@code submissionStatus = SUBMITTED, finalityStatus = OPTIMISTIC}。</p>
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
    /** 交易提交状态（SIGNING/SUBMITTED/INCLUDED/REORGED/REJECTED），默认 SUBMITTED。 */
    private SubmissionStatus submissionStatus;
    /** 最终性状态（OPTIMISTIC/FINALIZING/FINALIZED/UNKNOWN），默认 OPTIMISTIC。 */
    private FinalityStatus finalityStatus;
    /**
     * 失败分类（仅失败时有意义）。缺省 {@link FailureKind#DECLINE}：
     * 分不清时不计入熔断，避免误伤活着的收款通道。
     */
    private FailureKind failureKind;

    public static ConnectorPaymentResult ok(String connectorPaymentId, PaymentStatus status) {
        ConnectorPaymentResult r = new ConnectorPaymentResult();
        r.success = true;
        r.connectorPaymentId = connectorPaymentId;
        r.status = status;
        r.submissionStatus = SubmissionStatus.SUBMITTED;
        r.finalityStatus = FinalityStatus.OPTIMISTIC;
        return r;
    }

    public static ConnectorPaymentResult ok(String connectorPaymentId, PaymentStatus status, String txHash) {
        ConnectorPaymentResult r = ok(connectorPaymentId, status);
        r.transactionHash = txHash;
        return r;
    }

    /**
     * 失败结果，分类缺省 {@link FailureKind#DECLINE}（不熔断）。
     * 只有能给出结构化分类的调用点才应使用 {@link #fail(String, FailureKind)}。
     */
    public static ConnectorPaymentResult fail(String errorMessage) {
        return fail(errorMessage, FailureKind.DECLINE);
    }

    /**
     * 带失败分类的结果。{@code failureKind} 为 null 时按 {@link FailureKind#DECLINE} 处理。
     */
    public static ConnectorPaymentResult fail(String errorMessage, FailureKind failureKind) {
        ConnectorPaymentResult r = new ConnectorPaymentResult();
        r.success = false;
        r.status = PaymentStatus.FAILED;
        r.errorMessage = errorMessage;
        r.submissionStatus = SubmissionStatus.REJECTED;
        r.finalityStatus = FinalityStatus.UNKNOWN;
        r.failureKind = failureKind == null ? FailureKind.DECLINE : failureKind;
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

    /**
     * 链式填充交易提交状态。
     */
    public ConnectorPaymentResult withSubmissionStatus(SubmissionStatus submissionStatus) {
        this.submissionStatus = submissionStatus;
        return this;
    }

    /**
     * 链式填充最终性状态。
     */
    public ConnectorPaymentResult withFinalityStatus(FinalityStatus finalityStatus) {
        this.finalityStatus = finalityStatus;
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
    public SubmissionStatus getSubmissionStatus() { return submissionStatus; }
    public void setSubmissionStatus(SubmissionStatus submissionStatus) { this.submissionStatus = submissionStatus; }
    public FinalityStatus getFinalityStatus() { return finalityStatus; }
    public void setFinalityStatus(FinalityStatus finalityStatus) { this.finalityStatus = finalityStatus; }

    /** 失败分类；未设置时返回 {@link FailureKind#DECLINE}（不计入熔断）。 */
    public FailureKind getFailureKind() {
        return failureKind == null ? FailureKind.DECLINE : failureKind;
    }

    /** 设置失败分类；传 null 等价于 {@link FailureKind#DECLINE}。 */
    public void setFailureKind(FailureKind failureKind) {
        this.failureKind = failureKind == null ? FailureKind.DECLINE : failureKind;
    }
}
