package org.nexus.gateway.transaction;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 分布式事务日志实体（JPA）。
 *
 * <p>持久化 TCC/SAGA 分布式事务的每一步执行状态，支持故障恢复和超时处理。
 * 每条日志记录对应一个事务步骤（TCC 的 Try/Confirm/Cancel 或 SAGA 的每个步骤），
 * 通过 {@code transactionId} 关联同一事务的所有步骤。</p>
 *
 * <p>关键设计原则：</p>
 * <ul>
 *   <li>先写日志后执行操作 — 确保故障后可恢复</li>
 *   <li>幂等保证 — 通过 {@code transactionId} 去重，同一事务不会重复执行</li>
 *   <li>乐观锁 — {@code @Version} 防止并发修改冲突</li>
 * </ul>
 */
@Entity
@Table(name = "transaction_logs")
public class TransactionLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 事务编号（UUID），同一事务的所有步骤共享此 ID */
    @Column(name = "tx_id", nullable = false, length = 64)
    private String transactionId;

    /** 事务类型：TCC 或 SAGA */
    @Enumerated(EnumType.STRING)
    @Column(name = "tx_type", nullable = false, length = 8)
    private TransactionType transactionType;

    /** 步骤名称（如 "lockOrder"、"confirmPayment"、"releaseLock"） */
    @Column(name = "step_name", nullable = false, length = 64)
    private String stepName;

    /** 步骤序号：TCC(0=TRY, 1=CONFIRM, 2=CANCEL), SAGA(0..N) */
    @Column(name = "step_index", nullable = false)
    private Integer stepIndex;

    /** 步骤状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TransactionStepStatus stepStatus;

    /** 关联业务凭证（如 orderNo、refundNo） */
    @Column(name = "business_reference", nullable = false, length = 128)
    private String businessReference;

    /** 参与方标识（如 "payment-service"、"account-service"） */
    @Column(name = "participant_id", nullable = false, length = 64)
    private String participantId;

    /** JSON 格式载荷，存储事务上下文数据 */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    /** 错误信息（步骤失败时记录） */
    @Column(name = "error_message", length = 512)
    private String errorMessage;

    /** 多租户隔离键 */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
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

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public TransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }

    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }

    public Integer getStepIndex() { return stepIndex; }
    public void setStepIndex(Integer stepIndex) { this.stepIndex = stepIndex; }

    public TransactionStepStatus getStepStatus() { return stepStatus; }
    public void setStepStatus(TransactionStepStatus stepStatus) { this.stepStatus = stepStatus; }

    public String getBusinessReference() { return businessReference; }
    public void setBusinessReference(String businessReference) { this.businessReference = businessReference; }

    public String getParticipantId() { return participantId; }
    public void setParticipantId(String participantId) { this.participantId = participantId; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}