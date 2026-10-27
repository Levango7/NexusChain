package org.nexus.walletsvc.execution;

import java.util.Objects;

/**
 * 链上执行结果封装（P2-F3，wallet-service 本地副本）。
 *
 * <p>与 {@code org.nexus.gateway.execution.OnChainResult} 语义一致，
 * 因模块隔离在 wallet-service 中保留独立副本。</p>
 */
public final class OnChainResult {

    public enum Status {
        SUCCESS,
        PENDING_CONFIRMATION,
        /** 大额签名转人工审批（signing 返回 2001）：**钱未动**，等待审批通过后可重入执行。 */
        AWAITING_APPROVAL,
        /** 已执行但结果不可核对（如 sign-approved 报 EXECUTED 而本地无哈希）：**可能已动账**，不得回滚、需人工核对。 */
        EXECUTED_UNVERIFIED,
        FAILED
    }

    private final String txHash;
    private final Status status;
    private final String error;
    private final boolean simulated;
    /** 待审批态下的 signing 审批请求 ID（(c)-lite：持久化并在重试时复用，杜绝悬空审批）。 */
    private final String approvalId;

    public OnChainResult(String txHash, Status status, String error, boolean simulated) {
        this(txHash, status, error, simulated, null);
    }

    public OnChainResult(String txHash, Status status, String error, boolean simulated, String approvalId) {
        this.txHash = txHash;
        this.status = Objects.requireNonNull(status, "status");
        this.error = error;
        this.simulated = simulated;
        this.approvalId = approvalId;
    }

    public static OnChainResult success(String txHash, boolean simulated) {
        return new OnChainResult(txHash, Status.SUCCESS, null, simulated);
    }

    public static OnChainResult pending(String txHash, boolean simulated) {
        return new OnChainResult(txHash, Status.PENDING_CONFIRMATION, null, simulated);
    }

    /**
     * 大额签名待审批（signing 2001）：未动账，调用方应持久化 {@code approvalId} 并在后续重试时
     * **复用**（查询审批状态 / 审批通过后走 sign-approved），不得在重试时新建审批单。
     */
    public static OnChainResult awaitingApproval(String approvalId, boolean simulated) {
        return new OnChainResult(null, Status.AWAITING_APPROVAL, null, simulated, approvalId);
    }

    /**
     * 已执行但结果不可核对（可能已动账）：必须保持非终态留痕并转人工，**不得**回滚或置 FAILED。
     * 命名与 SDK/gateway 的 {@code EXECUTED_UNVERIFIED} 口径对齐（板 20261027-1543 §四）。
     */
    public static OnChainResult executedUnverified(String error, boolean simulated) {
        return new OnChainResult(null, Status.EXECUTED_UNVERIFIED, error, simulated);
    }

    public static OnChainResult failure(String error, boolean simulated) {
        return new OnChainResult(null, Status.FAILED, error, simulated);
    }

    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isFailed() { return status == Status.FAILED; }
    public boolean isPending() { return status == Status.PENDING_CONFIRMATION; }
    public boolean isAwaitingApproval() { return status == Status.AWAITING_APPROVAL; }
    public boolean isExecutedUnverified() { return status == Status.EXECUTED_UNVERIFIED; }

    public String getTxHash() { return txHash; }
    public Status getStatus() { return status; }
    public String getError() { return error; }
    public boolean isSimulated() { return simulated; }
    public String getApprovalId() { return approvalId; }

    @Override
    public String toString() {
        return "OnChainResult{txHash='" + txHash + '\'' + ", status=" + status
                + ", error='" + error + '\'' + ", simulated=" + simulated
                + ", approvalId='" + approvalId + '\'' + '}';
    }
}