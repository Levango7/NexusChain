package org.nexus.gateway.escrow;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预授权交易实体 — JPA 映射 preauth_transactions 表。
 *
 * <p>预授权生命周期：{@code AUTHORIZED → CAPTURED}（扣款，终态）、
 * {@code AUTHORIZED → VOIDED}（撤销，终态）、
 * {@code AUTHORIZED → EXPIRED}（超时自动释放，终态）。
 * 预授权先冻结金额，后续可全额或部分扣款，剩余金额释放回原账户。</p>
 *
 * <p>并发安全：使用 {@code @Version} 乐观锁，状态变更时自动检查版本号。</p>
 */
@Entity
@Table(name = "preauth_transactions")
public class PreAuthTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 预授权编号，格式 PA{timestamp}{random} */
    @Column(name = "preauth_no", unique = true, nullable = false, length = 64)
    private String preauthNo;

    /** 关联商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 关联支付订单 ID（可选） */
    @Column(name = "order_id")
    private Long orderId;

    /** 冻结金额 */
    @Column(name = "freeze_amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal freezeAmount;

    /** 已扣款金额（默认 0，部分扣款时更新） */
    @Column(name = "capture_amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal captureAmount = BigDecimal.ZERO;

    /** 预授权状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PreAuthStatus status = PreAuthStatus.AUTHORIZED;

    /** 冻结账户编号 */
    @Column(name = "frozen_account_id", nullable = false, length = 64)
    private String frozenAccountId;

    /** 超时自动释放天数（默认 3 天） */
    @Column(name = "auto_release_days", nullable = false)
    private Integer autoReleaseDays = 3;

    /** 多租户隔离键 */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    /** 乐观锁版本号 */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 授权时间 */
    @Column(name = "authorized_at")
    private LocalDateTime authorizedAt;

    /** 扣款时间 */
    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    /** 撤销时间 */
    @Column(name = "voided_at")
    private LocalDateTime voidedAt;

    /** 过期时间 */
    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = PreAuthStatus.AUTHORIZED;
        }
        if (this.autoReleaseDays == null) {
            this.autoReleaseDays = 3;
        }
        if (this.captureAmount == null) {
            this.captureAmount = BigDecimal.ZERO;
        }
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPreauthNo() { return preauthNo; }
    public void setPreauthNo(String preauthNo) { this.preauthNo = preauthNo; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public BigDecimal getFreezeAmount() { return freezeAmount; }
    public void setFreezeAmount(BigDecimal freezeAmount) { this.freezeAmount = freezeAmount; }

    public BigDecimal getCaptureAmount() { return captureAmount; }
    public void setCaptureAmount(BigDecimal captureAmount) { this.captureAmount = captureAmount; }

    public PreAuthStatus getStatus() { return status; }
    public void setStatus(PreAuthStatus status) { this.status = status; }

    public String getFrozenAccountId() { return frozenAccountId; }
    public void setFrozenAccountId(String frozenAccountId) { this.frozenAccountId = frozenAccountId; }

    public Integer getAutoReleaseDays() { return autoReleaseDays; }
    public void setAutoReleaseDays(Integer autoReleaseDays) { this.autoReleaseDays = autoReleaseDays; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getAuthorizedAt() { return authorizedAt; }
    public void setAuthorizedAt(LocalDateTime authorizedAt) { this.authorizedAt = authorizedAt; }

    public LocalDateTime getCapturedAt() { return capturedAt; }
    public void setCapturedAt(LocalDateTime capturedAt) { this.capturedAt = capturedAt; }

    public LocalDateTime getVoidedAt() { return voidedAt; }
    public void setVoidedAt(LocalDateTime voidedAt) { this.voidedAt = voidedAt; }

    public LocalDateTime getExpiredAt() { return expiredAt; }
    public void setExpiredAt(LocalDateTime expiredAt) { this.expiredAt = expiredAt; }
}