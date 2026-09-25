package org.nexus.gateway.account;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商户虚拟账户实体 — JPA 映射 merchant_accounts 表。
 *
 * <p>每个商户拥有三种类型的虚拟账户：{@link AccountType#BALANCE}（可用余额）、
 * {@link AccountType#FROZEN}（冻结资金）、{@link AccountType#RESERVE}（备付金）。
 * 账户间通过 {@link AccountOperationType#TRANSFER} 进行资金转移。</p>
 *
 * <p>并发安全：使用 {@code @Version} 乐观锁，余额变更时自动检查版本号，
 * 冲突时抛出 {@code OptimisticLockException}，由 Service 层重试。</p>
 *
 * <p>状态流转：{@code ACTIVE} → {@code FROZEN} → {@code ACTIVE}
 * 或 {@code ACTIVE} → {@code CLOSED}（终态）。</p>
 */
@Entity
@Table(name = "merchant_accounts")
public class MerchantAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 账户编号，格式 MA{merchantId}{accountType} */
    @Column(name = "account_id", unique = true, nullable = false, length = 64)
    private String accountId;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 账户类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 32)
    private AccountType accountType;

    /** 当前余额 */
    @Column(name = "balance", nullable = false, precision = 36, scale = 0)
    private BigDecimal balance = BigDecimal.ZERO;

    /** 账户状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AccountStatus status = AccountStatus.ACTIVE;

    /** 预警级别：NULL(正常)/WARNING(预警)/CRITICAL(严重)/EMERGENCY(紧急) */
    @Column(name = "alert_flag", length = 32)
    private String alertFlag;

    /** 多租户隔离键 */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    /** 乐观锁版本号 */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.balance == null) {
            this.balance = BigDecimal.ZERO;
        }
        if (this.status == null) {
            this.status = AccountStatus.ACTIVE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public AccountType getAccountType() { return accountType; }
    public void setAccountType(AccountType accountType) { this.accountType = accountType; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }

    public String getAlertFlag() { return alertFlag; }
    public void setAlertFlag(String alertFlag) { this.alertFlag = alertFlag; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}