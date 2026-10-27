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

    /**
     * 提现请求状态。
     *
     * <p>{@code EXECUTING}（2026-10-12 新增）：执行方在调用签名/广播**之前**用一条条件
     * UPDATE 把 {@code APPROVED → EXECUTING} 抢占下来（数据库行级锁互斥）。这样并发或
     * 重复的 execute 调用只有一个能继续，其余在抢占处即被拒绝 —— 修复此前"两个并发
     * 调用都能通过 APPROVED 检查、各自触发一次签名广播"的重复出款缺陷（乐观锁
     * {@code @Version} 只在写回时才冲突，那时链上已经广播，来不及了）。</p>
     *
     * <p>持久化为字符串（{@code @Enumerated(STRING)}），故插入枚举值不需要数据库迁移。</p>
     */
    public enum WithdrawalStatus {
        PENDING, APPROVED, EXECUTING, REJECTED, EXECUTED, FAILED,
        /**
         * 大额签名待人工审批（(c)-lite，2026-10-27）：signing 侧返回 2001，**链上未动账**。
         * 执行腿须持久化 signing approvalId，重试时**复用**该审批（查询状态；通过后走
         * sign-approved），不得新建审批单（悬空审批随重试线性增长是本状态要消灭的缺陷）。
         */
        AWAITING_APPROVAL,
        /**
         * 结果不可核对（(c)-lite，2026-10-27）：signing 报已执行但本地未记录到哈希等场景——
         * **可能已动账**，必须保持此态留痕并转人工核对，**不得**回滚或置 FAILED。
         */
        UNVERIFIED
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