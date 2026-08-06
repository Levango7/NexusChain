package org.nexus.wallet.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Wallet 端链上交易执行结果 DTO。
 * <p>
 * 与 gateway 端 {@code org.nexus.settlement.execution.TransactionResult}
 * 的 JSON 结构保持一致，通过 HTTP 传输。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WalletTransactionResult {

    /** 执行状态 */
    public enum Status {
        SUCCESS,
        PENDING_CONFIRMATION,
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

    public WalletTransactionResult() {
    }

    public WalletTransactionResult(String txHash, Status status, int confirmations,
                                    String error, boolean simulated) {
        this.txHash = txHash;
        this.status = status;
        this.confirmations = confirmations;
        this.error = error;
        this.simulated = simulated;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WalletTransactionResult)) return false;
        WalletTransactionResult that = (WalletTransactionResult) o;
        return confirmations == that.confirmations
                && simulated == that.simulated
                && Objects.equals(txHash, that.txHash)
                && status == that.status
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(txHash, status, confirmations, error, simulated);
    }
}