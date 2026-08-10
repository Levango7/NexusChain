package org.nexus.gateway.currency;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Currency wallet entity tracking a merchant's balance and frozen amount for
 * a single currency.
 *
 * <p>The available balance is {@code balance - frozenAmount}. Frozen amount
 * represents funds held in-flight (e.g. pending withdrawals or settlement).</p>
 */
@Entity
@Table(name = "currency_wallets", uniqueConstraints = {
        @UniqueConstraint(name = "uk_merchant_currency", columnNames = {"merchant_id", "currency"})
})
public class CurrencyWallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning merchant ID. */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** Wallet currency. */
    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 16)
    private Currency currency;

    /** Total balance in the smallest unit of the currency. */
    @Column(name = "balance", nullable = false, precision = 36, scale = 0)
    private BigDecimal balance = BigDecimal.ZERO;

    /** Frozen amount held in-flight. */
    @Column(name = "frozen_amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal frozenAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** Optimistic lock version for concurrent safety. */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public Currency getCurrency() { return currency; }
    public void setCurrency(Currency currency) { this.currency = currency; }

    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public BigDecimal getFrozenAmount() { return frozenAmount; }
    public void setFrozenAmount(BigDecimal frozenAmount) { this.frozenAmount = frozenAmount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}