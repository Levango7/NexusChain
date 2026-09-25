package org.nexus.gateway.voidreversal;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 当日撤销请求实体 — JPA 映射 void_requests 表。
 *
 * <p>撤销（VOID）是交易级全额回滚操作，将当日已支付交易恢复原状。
 * 撤销窗口：T+1 日 24:00 前可撤销，超过窗口只能冲正。</p>
 *
 * <p>撤销与退款互斥：已有退款的订单不能撤销。撤销成功后订单状态变为
 * {@link org.nexus.gateway.model.PaymentOrder.OrderStatus#VOIDED}（终态）。</p>
 *
 * <p>并发安全：使用 {@code @Version} 乐观锁保证撤销请求的并发安全。</p>
 */
@Entity
@Table(name = "void_requests")
public class VoidRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 撤销编号，格式 VD{timestamp}{random} */
    @Column(name = "void_no", unique = true, nullable = false, length = 64)
    private String voidNo;

    /** 关联原支付订单 ID */
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 关联商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 撤销金额（等于订单金额，全额撤销） */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** 撤销状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private VoidStatus status = VoidStatus.PENDING;

    /** 撤销原因 */
    @Column(name = "reason", length = 256)
    private String reason;

    /** 操作人 ID */
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

    /** 撤销完成时间（审批通过并执行完毕后设置） */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = VoidStatus.PENDING;
        }
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getVoidNo() { return voidNo; }
    public void setVoidNo(String voidNo) { this.voidNo = voidNo; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public VoidStatus getStatus() { return status; }
    public void setStatus(VoidStatus status) { this.status = status; }

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