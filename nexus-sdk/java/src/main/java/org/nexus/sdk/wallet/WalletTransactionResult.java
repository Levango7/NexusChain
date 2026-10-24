package org.nexus.sdk.wallet;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 钱包端链上交易执行结果 DTO。
 *
 * <p>与 gateway 端 {@code org.nexus.settlement.execution.TransactionResult}
 * 的 JSON 结构保持一致，通过 HTTP 传输。</p>
 *
 * <p>迁移历史：原位于 {@code org.nexus.wallet.wallet.execution.WalletTransactionResult}，
 * 在 P2 方向5「签名服务独立部署 PoC」中迁移至 nexus-sdk 共享层
 * （新包 {@code org.nexus.sdk.wallet}）。JSON 字段名与结构保持不变，
 * 仅 Java 包路径变更。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WalletTransactionResult {

    /** 执行状态 */
    public enum Status {
        SUCCESS,
        PENDING_CONFIRMATION,
        /**
         * 大额签名转人工审批（gateway 透传 signing-service 的 2001）。
         *
         * <p>非终态：调用方须保存 {@code approvalId} 并用同一 requestId 重试；
         * 不可当作失败（原实现对未知状态会反序列化失败并误报"gateway 不可达"）。</p>
         */
        AWAITING_APPROVAL,
        /** 签名已执行但调用方没有交易哈希（资金可能已转出，禁止自动重试） */
        EXECUTED_UNVERIFIED,
        FAILED
    }

    @JsonProperty("txHash")
    private String txHash;

    @JsonProperty("status")
    private Status status;

    @JsonProperty("confirmations")
    private int confirmations;

    @JsonProperty("error")
    private String error;

    @JsonProperty("simulated")
    private boolean simulated;

    /** 大额签名待审批时的审批请求 ID */
    @JsonProperty("approvalId")
    private String approvalId;

    public WalletTransactionResult() {
    }

    public WalletTransactionResult(String txHash, Status status, int confirmations,
                                    String error, boolean simulated) {
        this(txHash, status, confirmations, error, simulated, null);
    }

    public WalletTransactionResult(String txHash, Status status, int confirmations,
                                    String error, boolean simulated, String approvalId) {
        this.txHash = txHash;
        this.status = status;
        this.confirmations = confirmations;
        this.error = error;
        this.simulated = simulated;
        this.approvalId = approvalId;
    }

    /** 是否成功 */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getConfirmations() { return confirmations; }
    public void setConfirmations(int confirmations) { this.confirmations = confirmations; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public boolean isSimulated() { return simulated; }
    public void setSimulated(boolean simulated) { this.simulated = simulated; }

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WalletTransactionResult)) return false;
        WalletTransactionResult that = (WalletTransactionResult) o;
        return confirmations == that.confirmations
                && simulated == that.simulated
                && Objects.equals(txHash, that.txHash)
                && status == that.status
                && Objects.equals(error, that.error)
                && Objects.equals(approvalId, that.approvalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(txHash, status, confirmations, error, simulated, approvalId);
    }
}