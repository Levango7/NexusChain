package org.nexus.gateway.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment order entity representing a single payment request from a merchant.
 *
 * <p>An order transitions through the following lifecycle:
 * {@code PENDING -> PAYING -> PAID} or {@code PENDING -> EXPIRED},
 * and may transition to {@code REFUNDED} after a successful refund.</p>
 */
@Entity
@Table(name = "payment_orders")
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Globally unique order number shown to the payer. */
    @Column(name = "order_no", unique = true, nullable = false, length = 64)
    private String orderNo;

    /** Owning merchant ID. */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /**
     * 业务租户 ID（P4-T6 多租户改造，数据隔离键）。
     *
     * <p>对应 {@code tenants.tenant_id}，由 {@link org.nexus.gateway.tenant.TenantContext}
     * 在 Service 层填充。Repository 查询时按此字段过滤，确保租户间数据不可见。
     * 允许 {@code null} 以兼容多租户改造前的存量数据。</p>
     */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    /** Token symbol (always NEX for NexusChain). */
    @Column(name = "token_symbol", nullable = false, length = 16)
    private String tokenSymbol = "NEX";

    /** Payment amount in the smallest unit of the token. */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** Optional merchant-provided description / product title. */
    @Column(name = "description", length = 256)
    private String description;

    /** Payer's wallet address. */
    @Column(name = "payer_address", length = 66)
    private String payerAddress;

    /** Merchant's settlement wallet address. */
    @Column(name = "payee_address", nullable = false, length = 66)
    private String payeeAddress;

    /** On-chain transaction hash once payment is broadcast. */
    @Column(name = "chain_tx_hash", length = 128)
    private String chainTxHash;

    /** Current order status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private OrderStatus status = OrderStatus.PENDING;

    /** Checkout token for cashier redirect URL. */
    @Column(name = "checkout_token", unique = true, length = 128)
    private String checkoutToken;

    /** Order expiry time. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

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

    // --- Enumerations ---

    public enum OrderStatus {
        PENDING, PAYING, PAID, EXPIRED, REFUNDED, FAILED, REFUND_PENDING
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getTokenSymbol() { return tokenSymbol; }
    public void setTokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public String getPayeeAddress() { return payeeAddress; }
    public void setPayeeAddress(String payeeAddress) { this.payeeAddress = payeeAddress; }

    public String getChainTxHash() { return chainTxHash; }
    public void setChainTxHash(String chainTxHash) { this.chainTxHash = chainTxHash; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public String getCheckoutToken() { return checkoutToken; }
    public void setCheckoutToken(String checkoutToken) { this.checkoutToken = checkoutToken; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
