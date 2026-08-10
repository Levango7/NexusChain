package org.nexus.gateway.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Subscription entity representing a recurring payment agreement between
 * a merchant and a payer.
 *
 * <p>The subscription authorizes the merchant to periodically deduct NEX
 * from the payer's wallet via a SUBSCRIPTION_AUTH transaction type.</p>
 */
@Entity
@Table(name = "subscriptions")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Globally unique subscription identifier. */
    @Column(name = "subscription_no", unique = true, nullable = false, length = 64)
    private String subscriptionNo;

    /** Owning merchant ID. */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** Payer's wallet address authorized for recurring charges. */
    @Column(name = "payer_address", nullable = false, length = 66)
    private String payerAddress;

    /** Merchant's settlement wallet address. */
    @Column(name = "payee_address", nullable = false, length = 66)
    private String payeeAddress;

    /** Token symbol (always NEX). */
    @Column(name = "token_symbol", nullable = false, length = 16)
    private String tokenSymbol = "NEX";

    /** Amount charged per billing cycle. */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** Billing cycle interval (e.g. 1, 7, 30 days). */
    @Column(name = "cycle_days", nullable = false)
    private Integer cycleDays;

    /** Number of completed billing cycles. */
    @Column(name = "charged_count", nullable = false)
    private Integer chargedCount = 0;

    /** Current subscription status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SubscriptionStatus status = SubscriptionStatus.ACTIVE;

    /** On-chain subscription authorization transaction hash. */
    @Column(name = "auth_tx_hash", length = 128)
    private String authTxHash;

    /** Next scheduled charge time. */
    @Column(name = "next_charge_at", nullable = false)
    private LocalDateTime nextChargeAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

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

    // --- Enumerations ---

    public enum SubscriptionStatus {
        ACTIVE, SUSPENDED, CANCELLED, EXPIRED
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSubscriptionNo() { return subscriptionNo; }
    public void setSubscriptionNo(String subscriptionNo) { this.subscriptionNo = subscriptionNo; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public String getPayeeAddress() { return payeeAddress; }
    public void setPayeeAddress(String payeeAddress) { this.payeeAddress = payeeAddress; }

    public String getTokenSymbol() { return tokenSymbol; }
    public void setTokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Integer getCycleDays() { return cycleDays; }
    public void setCycleDays(Integer cycleDays) { this.cycleDays = cycleDays; }

    public Integer getChargedCount() { return chargedCount; }
    public void setChargedCount(Integer chargedCount) { this.chargedCount = chargedCount; }

    public SubscriptionStatus getStatus() { return status; }
    public void setStatus(SubscriptionStatus status) { this.status = status; }

    public String getAuthTxHash() { return authTxHash; }
    public void setAuthTxHash(String authTxHash) { this.authTxHash = authTxHash; }

    public LocalDateTime getNextChargeAt() { return nextChargeAt; }
    public void setNextChargeAt(LocalDateTime nextChargeAt) { this.nextChargeAt = nextChargeAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
}
