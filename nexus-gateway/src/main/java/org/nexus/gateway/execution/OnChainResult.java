package org.nexus.gateway.execution;

import java.util.Objects;

/**
 * 链上执行结果的统一封装（P2-F3 三阶段模式的阶段2 输出）。
 *
 * <p>由阶段2（链上执行）产出，传入阶段3（更新 CONFIRMED/FAILED），
 * 阶段3 根据本结果决定数据库记录的最终状态。</p>
 *
 * <ul>
 *   <li>{@code txHash}：链上交易哈希；执行失败时为 null</li>
 *   <li>{@code status}：执行状态（SUCCESS / FAILED / PENDING_CONFIRMATION）</li>
 *   <li>{@code error}：失败时的错误描述，成功时为 null</li>
 *   <li>{@code simulated}：是否为 sandbox/mock 模式产生的模拟结果</li>
 * </ul>
 */
public final class OnChainResult {

    /** 执行状态 */
    public enum Status {
        /** 已上链并达到所需确认数 */
        SUCCESS,
        /** 已广播但尚未达到所需确认数 */
        PENDING_CONFIRMATION,
        /** 执行失败（签名 / 广播 / 确认任一环节失败） */
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

    /** 构造成功结果 */
    public static OnChainResult success(String txHash, boolean simulated) {
        return new OnChainResult(txHash, Status.SUCCESS, null, simulated);
    }

    /** 构造待确认结果 */
    public static OnChainResult pending(String txHash, boolean simulated) {
        return new OnChainResult(txHash, Status.PENDING_CONFIRMATION, null, simulated);
    }

    /** 构造失败结果 */
    public static OnChainResult failure(String error, boolean simulated) {
        return new OnChainResult(null, Status.FAILED, error, simulated);
    }

    /** 是否成功（已上链并确认） */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    /** 是否失败 */
    public boolean isFailed() {
        return status == Status.FAILED;
    }

    /** 是否仍在待确认 */
    public boolean isPending() {
        return status == Status.PENDING_CONFIRMATION;
    }

    public String getTxHash() { return txHash; }
    public Status getStatus() { return status; }
    public String getError() { return error; }
    public boolean isSimulated() { return simulated; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OnChainResult)) return false;
        OnChainResult that = (OnChainResult) o;
        return simulated == that.simulated
                && Objects.equals(txHash, that.txHash)
                && status == that.status
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(txHash, status, error, simulated);
    }

    @Override
    public String toString() {
        return "OnChainResult{txHash='" + txHash + '\''
                + ", status=" + status
                + ", error='" + error + '\''
                + ", simulated=" + simulated + '}';
    }
}