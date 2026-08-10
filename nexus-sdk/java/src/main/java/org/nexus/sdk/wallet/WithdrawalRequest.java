package org.nexus.sdk.wallet;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 提现请求实体，跨服务传输 DTO。
 *
 * <p>原位于 {@code org.nexus.wallet.wallet.approval.WithdrawalRequest}（nexus-exchange-wallet），
 * 在 Phase 1 微服务化中迁移至 nexus-sdk 共享层（新包 {@code org.nexus.sdk.wallet}），
 * 供 nexus-wallet-service（提现审批）与 nexus-signing-service（执行提币签名广播）共享。</p>
 *
 * <p>生命周期：{@code PENDING -> APPROVED -> EXECUTED} 或
 * {@code PENDING -> REJECTED} 或 {@code APPROVED -> FAILED}。当
 * {@code approvedCount >= requiredApprovers} 时视为已批准。</p>
 */
public class WithdrawalRequest {

    /** Unique request ID. */
    private String requestId;

    /** Target wallet address to receive the withdrawal. */
    private String toAddress;

    /** Withdrawal amount in the smallest unit of the currency. */
    private BigDecimal amount;

    /** Currency symbol (e.g. NEX, USDT). */
    private String currency;

    /** Current request status. */
    private WithdrawalStatus status = WithdrawalStatus.PENDING;

    /** List of approver IDs that have approved the request. */
    private List<String> approvers = new ArrayList<>();

    /** Number of approvers required to release the withdrawal. */
    private Integer requiredApprovers;

    /** Number of approvers that have already approved. */
    private Integer approvedCount = 0;

    /** On-chain withdrawal transaction hash, set once executed. */
    private String chainTxHash;

    /** Rejection reason, populated when status = REJECTED. */
    private String rejectionReason;

    /** Timestamp when the request was created. */
    private LocalDateTime createdAt;

    /** Timestamp when the request was executed on-chain. */
    private LocalDateTime executedAt;

    public enum WithdrawalStatus {
        PENDING, APPROVED, REJECTED, EXECUTED, FAILED
    }

    // --- Getters and Setters ---

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public WithdrawalStatus getStatus() { return status; }
    public void setStatus(WithdrawalStatus status) { this.status = status; }

    public List<String> getApprovers() { return approvers; }
    public void setApprovers(List<String> approvers) { this.approvers = approvers; }

    public Integer getRequiredApprovers() { return requiredApprovers; }
    public void setRequiredApprovers(Integer requiredApprovers) { this.requiredApprovers = requiredApprovers; }

    public Integer getApprovedCount() { return approvedCount; }
    public void setApprovedCount(Integer approvedCount) { this.approvedCount = approvedCount; }

    public String getChainTxHash() { return chainTxHash; }
    public void setChainTxHash(String chainTxHash) { this.chainTxHash = chainTxHash; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
}