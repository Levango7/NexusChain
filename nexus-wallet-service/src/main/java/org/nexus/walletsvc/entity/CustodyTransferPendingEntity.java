package org.nexus.walletsvc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 托管出金待处置记录，映射 {@code custody_transfer_pending} 表（Flyway V5）。
 *
 * <p>存在的理由：{@code DefaultCustodyService} 的链上执行原来只按「txHash 是否为空」
 * 判失败并抛异常回滚冷热余额。#220 之后执行通道多了两种不可当作「确定未动账」的结果，
 * 两者的正确处置完全不同，都必须留下人工可取的记录：</p>
 *
 * <ul>
 *   <li>{@link PendingStatus#APPROVAL_OPEN}：大额转人工审批（2001），钱没出，
 *       余额回滚是对的；但签名侧审批请求仍开着，不留痕就没人知道有一笔被悬空的托管出金。</li>
 *   <li>{@link PendingStatus#VERIFY_REQUIRED}：签名已执行但取不到哈希，钱可能已经出，
 *       此时回滚余额 = 冷热归属与链上相反 = 对账缺口；所以不回滚，改为记这条待人工核对。</li>
 * </ul>
 *
 * <p>本行必须由 REQUIRES_NEW 独立事务写入：否则第一种情况的外层回滚会把它一起抹掉。
 * {@code request_key} 唯一，重复登记由数据库约束拒绝。</p>
 */
@Entity
@Table(name = "custody_transfer_pending")
public class CustodyTransferPendingEntity {

    /** 待处置状态；两种状态的人工动作不同，不可合并。 */
    public enum PendingStatus {
        /** 余额调整已回滚，钱未出账；处置 = 驳回审批或核对后重新发起。 */
        APPROVAL_OPEN,
        /** 余额调整已提交，钱可能已出；处置 = 核对链上后补记哈希或冲正。 */
        VERIFY_REQUIRED
    }

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 本次托管出金的幂等键（= 发给执行通道的 requestId）。 */
    @Column(name = "request_key", unique = true, nullable = false, length = 128)
    private String requestKey;

    /** 交易类型（SWEEP / WITHDRAWAL），字符串持久化，与 {@code WalletTransactionRequest.Type} 同名。 */
    @Column(name = "transfer_type", nullable = false, length = 16)
    private String transferType;

    /** 业务用途备注（memo）。 */
    @Column(name = "purpose", length = 128)
    private String purpose;

    /** 目标地址。 */
    @Column(name = "address", nullable = false, length = 128)
    private String address;

    /** 金额，36 位总精度 / 18 位小数（链上最小单位）。 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 18)
    private BigDecimal amount;

    /** 币种。 */
    @Column(name = "currency", length = 16)
    private String currency;

    /** 待处置状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PendingStatus status;

    /** 签名侧审批请求 ID（未分配时为 null）。 */
    @Column(name = "approval_id", length = 64)
    private String approvalId;

    /** 不可核对 / 失败原因，供人工排查。 */
    @Column(name = "error_note", length = 512)
    private String errorNote;

    /** 创建时间，由 {@link PrePersist} 自动维护。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间，由 {@link PreUpdate} 自动维护。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁版本号（{@link Version}），JPA 自动递增。 */
    @Version
    @Column(name = "version")
    private Long version;

    public CustodyTransferPendingEntity() {
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String requestKey) { this.requestKey = requestKey; }

    public String getTransferType() { return transferType; }
    public void setTransferType(String transferType) { this.transferType = transferType; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public PendingStatus getStatus() { return status; }
    public void setStatus(PendingStatus status) { this.status = status; }

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }

    public String getErrorNote() { return errorNote; }
    public void setErrorNote(String errorNote) { this.errorNote = errorNote; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
