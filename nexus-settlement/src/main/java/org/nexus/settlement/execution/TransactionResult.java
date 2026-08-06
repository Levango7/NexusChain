package org.nexus.settlement.execution;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 链上交易执行结果的统一封装。
 * <p>
 * 由 {@link OnChainExecutionChannel#execute} 与
 * {@link OnChainExecutionChannel#queryStatus} 返回，描述链上交易的最终状态。
 * </p>
 *
 * <ul>
 *   <li>{@code txHash}：链上交易哈希；sandbox/mock 模式下以 "SIMULATED-" 前缀标记</li>
 *   <li>{@code status}：执行状态（SUCCESS / FAILED / PENDING_CONFIRMATION）</li>
 *   <li>{@code confirmations}：当前确认数；未确认时为 0</li>
 *   <li>{@code error}：失败时的错误描述，成功时为 null</li>
 *   <li>{@code simulated}：是否为 sandbox/mock 模式产生的模拟结果</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionResult {

    /** 执行状态 */
    public enum Status {
        /** 已上链并达到所需确认数 */
        SUCCESS,
        /** 已广播但尚未达到所需确认数 */
        PENDING_CONFIRMATION,
        /** 执行失败（签名 / 广播 / 确认任一环节失败） */
        FAILED
    }

    /** 链上交易哈希 */
    @JsonProperty("txHash")
    private String txHash;

    /** 执行状态 */
    @JsonProperty("status")
    private Status status;

    /** 当前确认数 */
    @JsonProperty("confirmations")
    private int confirmations;

    /** 失败错误描述 */
    @JsonProperty("error")
    private String error;

    /** 是否为 sandbox/mock 模式产生的模拟结果 */
    @JsonProperty("simulated")
    private boolean simulated;

    public TransactionResult() {
    }

    public TransactionResult(String txHash, Status status, int confirmations, String error, boolean simulated) {
        this.txHash = txHash;
        this.status = status;
        this.confirmations = confirmations;
        this.error = error;
        this.simulated = simulated;
    }

    /** 构造成功结果 */
    public static TransactionResult success(String txHash, int confirmations, boolean simulated) {
        return new TransactionResult(txHash, Status.SUCCESS, confirmations, null, simulated);
    }

    /** 构造待确认结果 */
    public static TransactionResult pending(String txHash, int confirmations, boolean simulated) {
        return new TransactionResult(txHash, Status.PENDING_CONFIRMATION, confirmations, null, simulated);
    }

    /** 构造失败结果 */
    public static TransactionResult failure(String error, boolean simulated) {
        return new TransactionResult(null, Status.FAILED, 0, error, simulated);
    }

    /** 是否成功 */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    // --- Getters and Setters ---

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
        if (!(o instanceof TransactionResult)) return false;
        TransactionResult that = (TransactionResult) o;
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

    @Override
    public String toString() {
        return "TransactionResult{txHash='" + txHash + '\''
                + ", status=" + status
                + ", confirmations=" + confirmations
                + ", error='" + error + '\''
                + ", simulated=" + simulated + '}';
    }
}