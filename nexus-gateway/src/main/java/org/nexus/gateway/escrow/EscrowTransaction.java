package org.nexus.gateway.escrow;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 担保交易实体 — JPA 映射 escrow_transactions 表。
 *
 * <p>担保交易生命周期：{@code CREATED → FUNDED → CONFIRMED → RELEASED}
 * 或 {@code CREATED → FUNDED → REFUNDED}。资金在 FUNDED 状态下冻结在担保账户，
 * 确认收货后释放给商户，退款则退回买家。</p>
 *
 * <p>并发安全：使用 {@code @Version} 乐观锁，状态变更时自动检查版本号。</p>
 */
@Entity
@Table(name = "escrow_transactions")
public class EscrowTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 担保交易编号，格式 EC{timestamp}{random} */
    @Column(name = "escrow_no", unique = true, nullable = false, length = 64)
    private String escrowNo;

    /** 关联商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 关联支付订单 ID */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 担保金额 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** 担保状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private EscrowStatus status = EscrowStatus.CREATED;

    /** 买家钱包地址 */
    @Column(name = "buyer_address", nullable = false, length = 66)
    private String buyerAddress;

    /** 担保冻结账户编号 */
    @Column(name = "escrow_account_id", nullable = false, length = 64)
    private String escrowAccountId;

    /** 超时自动确认天数（默认 7 天） */
    @Column(name = "auto_confirm_days", nullable = false)
    private Integer autoConfirmDays = 7;

    /** 多租户隔离键 */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    /** 乐观锁版本号 */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 买家付款时间 */
    @Column(name = "funded_at")
    private LocalDateTime fundedAt;

    /** 确认收货时间 */
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    /** 资金释放时间 */
    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = EscrowStatus.CREATED;
        }
        if (this.autoConfirmDays == null) {
            this.autoConfirmDays = 7;
        }
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEscrowNo() { return escrowNo; }
    public void setEscrowNo(String escrowNo) { this.escrowNo = escrowNo; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public EscrowStatus getStatus() { return status; }
    public void setStatus(EscrowStatus status) { this.status = status; }

    public String getBuyerAddress() { return buyerAddress; }
    public void setBuyerAddress(String buyerAddress) { this.buyerAddress = buyerAddress; }

    public String getEscrowAccountId() { return escrowAccountId; }
    public void setEscrowAccountId(String escrowAccountId) { this.escrowAccountId = escrowAccountId; }

    public Integer getAutoConfirmDays() { return autoConfirmDays; }
    public void setAutoConfirmDays(Integer autoConfirmDays) { this.autoConfirmDays = autoConfirmDays; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getFundedAt() { return fundedAt; }
    public void setFundedAt(LocalDateTime fundedAt) { this.fundedAt = fundedAt; }

    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }

    public LocalDateTime getReleasedAt() { return releasedAt; }
    public void setReleasedAt(LocalDateTime releasedAt) { this.releasedAt = releasedAt; }
}