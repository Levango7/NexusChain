package org.nexus.gateway.account;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 账户流水实体 — JPA 映射 account_transactions 表。
 *
 * <p>记录每一笔账户余额变更的不可篡改审计流水。每条流水包含操作前余额
 * ({@code balanceBefore}) 和操作后余额 ({@code balanceAfter})，确保可追溯。</p>
 *
 * <p>流水一旦写入不可修改（无 update 操作），仅作为审计依据。</p>
 */
@Entity
@Table(name = "account_transactions")
public class AccountTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 流水编号，格式 AT{timestamp}{random} */
    @Column(name = "tx_no", unique = true, nullable = false, length = 64)
    private String txNo;

    /** 关联账户编号 */
    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    /** 关联商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 操作类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 32)
    private AccountOperationType operationType;

    /** 方向：CREDIT(入账) / DEBIT(出账) */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private TransactionDirection direction;

    /** 操作金额，必须 > 0 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** 操作前余额 */
    @Column(name = "balance_before", nullable = false, precision = 36, scale = 0)
    private BigDecimal balanceBefore;

    /** 操作后余额 */
    @Column(name = "balance_after", nullable = false, precision = 36, scale = 0)
    private BigDecimal balanceAfter;

    /** 关联业务凭证（orderNo/refundNo/escrowNo/preauthNo/voidNo/reversalNo） */
    @Column(name = "reference", nullable = false, length = 128)
    private String reference;

    /** 多租户隔离键 */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTxNo() { return txNo; }
    public void setTxNo(String txNo) { this.txNo = txNo; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public AccountOperationType getOperationType() { return operationType; }
    public void setOperationType(AccountOperationType operationType) { this.operationType = operationType; }

    public TransactionDirection getDirection() { return direction; }
    public void setDirection(TransactionDirection direction) { this.direction = direction; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public BigDecimal getBalanceBefore() { return balanceBefore; }
    public void setBalanceBefore(BigDecimal balanceBefore) { this.balanceBefore = balanceBefore; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}