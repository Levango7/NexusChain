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
import org.nexus.sdk.wallet.WithdrawalRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 提现审批请求 Entity，映射 {@code withdrawal_requests} 表。
 *
 * <p>替代 {@code DefaultWithdrawalApprovalService.requests}
 * （{@code ConcurrentHashMap<String, WithdrawalRequest>}）内存存储
 * （Phase 4 任务 #69，设计文档 §4.1.3 / §4.2.1）。</p>
 *
 * <p>状态字段 {@link #status} 复用 SDK DTO {@link WithdrawalRequest.WithdrawalStatus}
 * 枚举，通过 {@link Enumerated}({@link EnumType#STRING}) 持久化为字符串，
 * 保证数据库可读性与枚举类型安全。并发状态流转通过 {@link Version} 乐观锁保护。</p>
 *
 * <p>审批人列表（{@code withdrawal_approvers} 一对多）<strong>不</strong>使用 {@code @OneToMany}
 * 关联自动加载，由 Service 层显式查询 {@code WithdrawalApproverRepository} 后通过
 * {@link WithdrawalRequestMapper#toDto} 注入 DTO，避免 N+1 查询与级联复杂性
 * （设计文档 §4.2.1 Mapper 签名 {@code toDto(entity, List<WithdrawalApproverEntity>)}）。</p>
 */
@Entity
@Table(name = "withdrawal_requests")
public class WithdrawalRequestEntity {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务请求 ID（{@code WD-<uuid>}），唯一约束 {@code uk_request_id}。 */
    @Column(name = "request_id", unique = true, nullable = false, length = 64)
    private String requestId;

    /** 目标提现地址。 */
    @Column(name = "to_address", nullable = false, length = 128)
    private String toAddress;

    /** 提现金额，36 位总精度 / 18 位小数。 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 18)
    private BigDecimal amount;

    /** 币种（如 NEX / USDT）。 */
    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    /** 当前状态，复用 SDK 枚举，字符串持久化。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private WithdrawalRequest.WithdrawalStatus status = WithdrawalRequest.WithdrawalStatus.PENDING;

    /** 所需审批人数。 */
    @Column(name = "required_approvers", nullable = false)
    private Integer requiredApprovers;

    /** 已审批人数，默认 0。 */
    @Column(name = "approved_count", nullable = false)
    private Integer approvedCount = 0;

    /** 链上交易哈希（EXECUTED 后填充），可空。 */
    @Column(name = "chain_tx_hash", length = 128)
    private String chainTxHash;

    /** 拒绝原因（REJECTED / FAILED 时填充），可空。 */
    @Column(name = "rejection_reason", length = 256)
    private String rejectionReason;

    /** 创建时间，由 {@link PrePersist} 自动维护。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 执行时间（EXECUTED 后填充），可空。 */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** 更新时间，由 {@link PreUpdate} 自动维护。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁版本号（{@link Version}），JPA 自动递增。 */
    @Version
    @Column(name = "version")
    private Long version;

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

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public WithdrawalRequest.WithdrawalStatus getStatus() { return status; }
    public void setStatus(WithdrawalRequest.WithdrawalStatus status) { this.status = status; }

    public Integer getRequiredApprovers() { return requiredApprovers; }
    public void setRequiredApprovers(Integer requiredApprovers) { this.requiredApprovers = requiredApprovers; }

    public Integer getApprovedCount() { return approvedCount; }
    public void setApprovedCount(Integer approvedCount) { this.approvedCount = approvedCount; }

    public String getChainTxHash() { return chainTxHash; }
    public void setChainTxHash(String chainTxHash) { this.chainTxHash = chainTxHash; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}