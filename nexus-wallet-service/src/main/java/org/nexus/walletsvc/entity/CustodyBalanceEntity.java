package org.nexus.walletsvc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 托管余额 Entity，映射 {@code custody_balances} 表。
 *
 * <p>替代 {@code DefaultCustodyService} 的 {@code hotBalance} / {@code coldBalance}
 * 两个 {@code AtomicReference<BigDecimal>} 内存存储（Phase 4 任务 #69，设计文档 §4.1.1 / §4.2.1）。</p>
 *
 * <p>采用以 {@code tier} 为主键的多行设计（而非单行表），便于未来扩展 WARM 层级。
 * 并发变更通过 {@link Version} 乐观锁保护，避免余额脏读 / 脏写。</p>
 *
 * <p>风格对齐 gateway 的 {@code PaymentOrder}：使用 {@link PrePersist} / {@link PreUpdate}
 * 自动维护时间戳字段。</p>
 */
@Entity
@Table(name = "custody_balances")
public class CustodyBalanceEntity {

    /** 托管层级：{@code HOT} / {@code WARM} / {@code COLD}，作为主键。 */
    @Id
    @Column(name = "tier", length = 16)
    private String tier;

    /** 余额，36 位总精度 / 18 位小数（覆盖链上最小单位）。 */
    @Column(name = "balance", nullable = false, precision = 36, scale = 18)
    private BigDecimal balance = BigDecimal.ZERO;

    /** 最后更新时间，由 {@link PreUpdate} 自动维护。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁版本号（{@link Version}），JPA 自动递增。 */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public String getTier() { return tier; }
    public void setTier(String tier) { this.tier = tier; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}