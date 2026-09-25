package org.nexus.gateway.clearing;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 清算结算记录实体 — JPA 映射 clearing_settlement_records 表。
 *
 * <p>记录清算批次入账和结算划拨的全过程，支持对账追溯。每条记录包含：</p>
 * <ul>
 *   <li>记录类型（CLEARING/SETTLEMENT_TRANSFER）</li>
 *   <li>关联清算批次编号</li>
 *   <li>商户ID、金额、方向（CREDIT/DEBIT）</li>
 *   <li>入账状态（PENDING/BOOKED/FAILED）</li>
 * </ul>
 */
@Entity
@Table(name = "clearing_settlement_records")
public class ClearingSettlementRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 记录编号，唯一标识 */
    @Column(name = "record_no", unique = true, nullable = false, length = 64)
    private String recordNo;

    /** 记录类型: CLEARING/SETTLEMENT_TRANSFER */
    @Column(name = "record_type", nullable = false, length = 32)
    private String recordType;

    /** 关联清算批次编号 */
    @Column(name = "batch_no", length = 64)
    private String batchNo;

    /** 商户ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 金额 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal amount;

    /** 方向: CREDIT/DEBIT */
    @Column(name = "direction", nullable = false, length = 8)
    private String direction;

    /** 入账状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "booking_status", nullable = false, length = 32)
    private BookingStatus bookingStatus = BookingStatus.PENDING;

    /** 关联账户编号 */
    @Column(name = "account_id", length = 64)
    private String accountId;

    /** 操作类型（对应 AccountOperationType） */
    @Column(name = "operation_type", length = 32)
    private String operationType;

    /** 转账关联凭证 */
    @Column(name = "transfer_reference", length = 128)
    private String transferReference;

    /** 失败原因（BOOKED=失败时记录） */
    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /** 结算时间 */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;

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
        if (this.bookingStatus == null) {
            this.bookingStatus = BookingStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRecordNo() { return recordNo; }
    public void setRecordNo(String recordNo) { this.recordNo = recordNo; }

    public String getRecordType() { return recordType; }
    public void setRecordType(String recordType) { this.recordType = recordType; }

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public BookingStatus getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(BookingStatus bookingStatus) { this.bookingStatus = bookingStatus; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }

    public String getTransferReference() { return transferReference; }
    public void setTransferReference(String transferReference) { this.transferReference = transferReference; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getSettledAt() { return settledAt; }
    public void setSettledAt(LocalDateTime settledAt) { this.settledAt = settledAt; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}