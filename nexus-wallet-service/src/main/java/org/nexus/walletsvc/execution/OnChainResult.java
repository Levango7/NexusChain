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
        /**
         * 签名侧转为人工审批，<b>钱没出</b>（2026-10-27，(c)-lite）。
         *
         * <p>与 {@link #FAILED} 的关键区别：FAILED 表示"确定没出去、这条单子结束"，
         * 而本状态表示"单子还活着，等签名侧审批通过后由推进机制继续执行"。
         * 混淆两者会让大额提现永久停在 FAILED（钱没出但单子死了），
         * 同时把签名侧的审批请求变成悬空审批。</p>
         */
        AWAITING_APPROVAL,
        /**
         * 已执行但结果不可核对——<b>钱可能已经出去</b>（2026-10-27）。
         *
         * <p>典型来源：签名侧报 {@code EXECUTED} 但我们没拿到哈希；或上次执行请求
         * 结果不明（网络失败），而签名侧审批又因 TTL 从 {@code EXECUTING} 回退到
         * {@code APPROVED}。此时<b>不能重试</b>（会二次放款），也<b>不能</b>当成
         * "确定未动账"的 FAILED 处理。</p>
         *
         * <p>与 SDK 的 {@code WalletTransactionResult.Status.EXECUTED_UNVERIFIED}
         * 语义对齐（本类是 wallet 本地副本，未共享 SDK 枚举）。</p>
         */
        EXECUTED_UNVERIFIED,
        FAILED
    }

    private final String txHash;
    private final Status status;
    private final String error;
    private final boolean simulated;
    /** 签名侧审批请求 ID，仅 {@link Status#AWAITING_APPROVAL} 非空。 */
    private final String approvalId;

    public OnChainResult(String txHash, Status status, String error, boolean simulated) {
        this(txHash, status, error, simulated, null);
    }

    public OnChainResult(String txHash, Status status, String error, boolean simulated,
                         String approvalId) {
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

    public static OnChainResult failure(String error, boolean simulated) {
        return new OnChainResult(null, Status.FAILED, error, simulated);
    }

    /**
     * 签名侧转人工审批，尚未动账（{@code statusCode=2001}）。
     *
     * <p>{@code message} 存进 {@link #getError()} 仅用于诊断（"为什么在等审批"），
     * 调用方不应把它当成失败原因展示给用户。</p>
     *
     * @param approvalId 签名侧审批请求 ID，必须非空——缺 ID 就无法推进，
     *                   那种情况应由调用方判为 FAILED（无法跟踪）
     * @param message    诊断信息
     */
    public static OnChainResult awaitingApproval(String approvalId, String message) {
        return new OnChainResult(null, Status.AWAITING_APPROVAL, message, false,
                Objects.requireNonNull(approvalId, "approvalId"));
    }

    /**
     * 已执行但结果不可核对（钱可能已出）。
     *
     * <p>调用方必须<b>人工核对</b>，不得自动重试。</p>
     */
    public static OnChainResult executedUnverified(String message) {
        return new OnChainResult(null, Status.EXECUTED_UNVERIFIED, message, false);
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