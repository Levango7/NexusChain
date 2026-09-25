package org.nexus.gateway.voidreversal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 隔日冲正请求实体 — JPA 映射 reversal_requests 表。
 *
 * <p>冲正（REVERSAL）是对已结算交易的反向调整操作，适用于超过撤销窗口的隔日交易。
 * 冲正生成反向交易记录，调用 AccountService 调整商户余额，订单状态变为
 * {@link org.nexus.gateway.model.PaymentOrder.OrderStatus#REVERSED}（终态）。</p>
 *
 * <p>冲正类型：</p>
 * <ul>
 *   <li>{@link ReversalType#MANUAL} — 人工发起，需审批后执行</li>
 *   <li>{@link ReversalType#AUTO} — 系统自动发起（由 AutoReversalScheduler 触发）</li>
 * </ul>
 *
 * <p>并发安全：使用 {@code @Version} 乐观锁保证冲正请求的并发安全。</p>
 */
@Entity
@Table(name = "reversal_requests")
public class ReversalRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 冲正编号，格式 RV{timestamp}{random} */
    @Column(name = "reversal_no", unique = true, nullable = false, length = 64)
    private String reversalNo;

    /** 关联原支付订单 ID */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 关联商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 冲正金额（等于订单金额，全额冲正） */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** 冲正状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReversalStatus status = ReversalStatus.PENDING;

    /** 冲正类型：MANUAL（人工）/ AUTO（自动） */
    @Enumerated(EnumType.STRING)
    @Column(name = "reversal_type", nullable = false, length = 8)
    private ReversalType reversalType = ReversalType.MANUAL;

    /** 冲正原因 */
    @Column(name = "reason", length = 256)
    private String reason;

    /** 操作人 ID（AUTO 类型时为 SYSTEM） */
    @Column(name = "operator_id", nullable = false, length = 64)
    private String operatorId;

    /** 多租户隔离键 */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    /** 乐观锁版本号 */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 冲正完成时间（审批通过并执行完毕后设置） */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = ReversalStatus.PENDING;
        }
        if (this.reversalType == null) {
            this.reversalType = ReversalType.MANUAL;
        }
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getReversalNo() { return reversalNo; }
    public void setReversalNo(String reversalNo) { this.reversalNo = reversalNo; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public ReversalStatus getStatus() { return status; }
    public void setStatus(ReversalStatus status) { this.status = status; }

    public ReversalType getReversalType() { return reversalType; }
    public void setReversalType(ReversalType reversalType) { this.reversalType = reversalType; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}