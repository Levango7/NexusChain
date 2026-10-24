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
 *   <li>{@code status}：执行状态（SUCCESS / PENDING_CONFIRMATION / AWAITING_APPROVAL /
 *       EXECUTED_UNVERIFIED / FAILED）</li>
 *   <li>{@code confirmations}：当前确认数；未确认时为 0</li>
 *   <li>{@code error}：失败时的错误描述，成功时为 null</li>
 *   <li>{@code simulated}：是否为 sandbox/mock 模式产生的模拟结果</li>
 *   <li>{@code approvalId}：大额签名转人工审批时的审批请求 ID（仅 AWAITING_APPROVAL /
 *       EXECUTED_UNVERIFIED 携带）</li>
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
        /**
         * 大额签名转人工审批：交易尚未签名/广播，等待审批通过后由调用方重试执行。
         *
         * <p>非终态——调用方必须保存 {@code approvalId} 并周期重试（同一 {@code requestId}），
         * 不得当作失败处理（这正是 2001 曾被误判为签名失败的修复点）。</p>
         */
        AWAITING_APPROVAL,
        /**
         * 签名服务报告已执行，但调用方没有拿到交易哈希（结果不可核对）。
         *
         * <p>非终态但<b>不得自动重试</b>：资金可能已经转出，重复执行会造成二次打款。
         * 必须人工核对链上交易后处置。</p>
         */
        EXECUTED_UNVERIFIED,
        /** 执行失败（签名 / 广播 / 确认任一环节失败；确定未动账） */
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

    /** 大额签名待审批时的审批请求 ID（仅 AWAITING_APPROVAL / EXECUTED_UNVERIFIED） */
    @JsonProperty("approvalId")
    private String approvalId;

    public TransactionResult() {
    }

    public TransactionResult(String txHash, Status status, int confirmations, String error, boolean simulated) {
        this(txHash, status, confirmations, error, simulated, null);
    }

    public TransactionResult(String txHash, Status status, int confirmations, String error,
                             boolean simulated, String approvalId) {
        this.txHash = txHash;
        this.status = status;
        this.confirmations = confirmations;
        this.error = error;
        this.simulated = simulated;
        this.approvalId = approvalId;
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

    /** 构造"大额签名待人工审批"结果（非终态，调用方须保存 approvalId 后重试） */
    public static TransactionResult awaitingApproval(String approvalId) {
        return new TransactionResult(null, Status.AWAITING_APPROVAL, 0, null, false, approvalId);
    }

    /**
     * 构造"签名已执行但结果不可核对"结果（资金可能已转出，禁止自动重试）。
     *
     * @param approvalId 审批请求 ID
     * @param error      不可核对的原因（供人工排查）
     */
    public static TransactionResult executedUnverified(String approvalId, String error) {
        return new TransactionResult(null, Status.EXECUTED_UNVERIFIED, 0, error, false, approvalId);
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

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TransactionResult)) return false;
        TransactionResult that = (TransactionResult) o;
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

    @Override
    public String toString() {
        return "TransactionResult{txHash='" + txHash + '\''
                + ", status=" + status
                + ", confirmations=" + confirmations
                + ", error='" + error + '\''
                + ", simulated=" + simulated
                + ", approvalId='" + approvalId + '\''
                + '}';
    }
}