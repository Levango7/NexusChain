package org.nexus.walletsvc.approval;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 提现申请 DTO（钱包管理服务侧）。
 *
 * <p>原实现位于 {@code org.nexus.wallet.wallet.approval.WithdrawalRequest}（exchange-wallet），
 * 本类为独立部署后的服务边界 DTO 骨架。</p>
 *
 * <p>PoC 阶段：仅定义核心字段与状态枚举，实际持久化与状态机逻辑待完整迁移。</p>
 */
public class WithdrawalRequest {

    /** 提现状态 */
    public enum WithdrawalStatus {
        PENDING,
        APPROVED,
        REJECTED,
        EXECUTED,
        FAILED
    }

    private String approvalId;
    private String to;
    private BigDecimal amount;
    private String currency;
    private int requiredApprovers;
    private int approvedCount;
    private WithdrawalStatus status;
    private LocalDateTime createdAt;

    public WithdrawalRequest() {
    }

    public WithdrawalRequest(String approvalId, String to, BigDecimal amount, String currency) {
        this.approvalId = approvalId;
        this.to = to;
        this.amount = amount;
        this.currency = currency;
        this.status = WithdrawalStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }

    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public int getRequiredApprovers() { return requiredApprovers; }
    public void setRequiredApprovers(int requiredApprovers) { this.requiredApprovers = requiredApprovers; }

    public int getApprovedCount() { return approvedCount; }
    public void setApprovedCount(int approvedCount) { this.approvedCount = approvedCount; }

    public WithdrawalStatus getStatus() { return status; }
    public void setStatus(WithdrawalStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}