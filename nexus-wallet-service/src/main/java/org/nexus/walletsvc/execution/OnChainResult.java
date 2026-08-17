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
        FAILED
    }

    private final String txHash;
    private final Status status;
    private final String error;
    private final boolean simulated;

    public OnChainResult(String txHash, Status status, String error, boolean simulated) {
        this.txHash = txHash;
        this.status = Objects.requireNonNull(status, "status");
        this.error = error;
        this.simulated = simulated;
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

    public boolean isSuccess() { return status == Status.SUCCESS; }
    public boolean isFailed() { return status == Status.FAILED; }
    public boolean isPending() { return status == Status.PENDING_CONFIRMATION; }

    public String getTxHash() { return txHash; }
    public Status getStatus() { return status; }
    public String getError() { return error; }
    public boolean isSimulated() { return simulated; }

    @Override
    public String toString() {
        return "OnChainResult{txHash='" + txHash + '\'' + ", status=" + status
                + ", error='" + error + '\'' + ", simulated=" + simulated + '}';
    }
}