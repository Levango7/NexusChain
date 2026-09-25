package org.nexus.gateway.risk.link;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 风控联动记录实体 — 记录风控事件与账户操作的联动执行记录。
 *
 * <p>每次风控事件触发账户联动操作时，生成一条 RiskAccountLinkRecord，
 * 用于审计追踪、幂等检查和联动闭环管理。</p>
 *
 * <p>幂等保证：{@code (riskEventId, linkAction)} 联合唯一约束确保
 * 同一风控事件 + 同一联动动作不会重复执行。</p>
 */
@Entity
@Table(name = "risk_account_link_records",
        uniqueConstraints = @UniqueConstraint(name = "idx_risk_event_link_action",
                columnNames = {"risk_event_id", "link_action"}))
public class RiskAccountLinkRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 风控事件 ID */
    @Column(name = "risk_event_id", nullable = false, length = 64)
    private String riskEventId;

    /** 联动动作 */
    @Enumerated(EnumType.STRING)
    @Column(name = "link_action", nullable = false, length = 32)
    private LinkAction linkAction;

    /** 执行状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", nullable = false, length = 32)
    private ExecutionStatus executionStatus = ExecutionStatus.PENDING;

    /** 商户 ID */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 冻结/解冻金额（状态变更类动作可为空） */
    @Column(name = "amount", precision = 36, scale = 8)
    private BigDecimal amount;

    /** 联动原因 */
    @Column(name = "reason", length = 512)
    private String reason;

    /** 执行失败时的错误信息 */
    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    /** 执行时间 */
    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** 完成时间 */
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

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

    public String getRiskEventId() { return riskEventId; }
    public void setRiskEventId(String riskEventId) { this.riskEventId = riskEventId; }

    public LinkAction getLinkAction() { return linkAction; }
    public void setLinkAction(LinkAction linkAction) { this.linkAction = linkAction; }

    public ExecutionStatus getExecutionStatus() { return executionStatus; }
    public void setExecutionStatus(ExecutionStatus executionStatus) { this.executionStatus = executionStatus; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}