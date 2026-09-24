package org.nexus.settlement.risk.action;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 风控处置记录实体 — 记录每次风控处置的执行结果。
 *
 * <p>每次风控评估触发处置动作时，生成一条 RiskActionRecord，
 * 用于审计追踪、处置效果分析和风控闭环管理。</p>
 */
@Entity
@Table(name = "risk_action_records")
public class RiskActionRecord {

    /** 处置动作枚举 */
    public enum ActionType {
        /** 告警 */
        ALERT,
        /** 阻断 */
        BLOCK,
        /** 人工审核 */
        MANUAL_REVIEW,
        /** 捕获并放行 */
        CAPTURE
    }

    /** 处置状态枚举 */
    public enum ActionStatus {
        /** 已执行 */
        EXECUTED,
        /** 执行失败 */
        FAILED,
        /** 待执行 */
        PENDING,
        /** 已撤销 */
        REVOKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 处置动作 */
    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 32)
    private ActionType actionType;

    /** 处置状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "action_status", nullable = false, length = 32)
    private ActionStatus actionStatus = ActionStatus.EXECUTED;

    /** 关联商户 ID */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** 关联订单号 */
    @Column(name = "order_id", length = 64)
    private String orderId;

    /** 风控评分 */
    @Column(name = "risk_score")
    private Integer riskScore;

    /** 风控决策 */
    @Column(name = "risk_decision", length = 32)
    private String riskDecision;

    /** 触发的规则列表（逗号分隔） */
    @Column(name = "triggered_rules", length = 1024)
    private String triggeredRules;

    /** 处置描述 */
    @Column(name = "description", length = 512)
    private String description;

    /** 处置执行时间 */
    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;

    /** 处置完成时间 */
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
        if (this.executedAt == null) {
            this.executedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ActionType getActionType() { return actionType; }
    public void setActionType(ActionType actionType) { this.actionType = actionType; }

    public ActionStatus getActionStatus() { return actionStatus; }
    public void setActionStatus(ActionStatus actionStatus) { this.actionStatus = actionStatus; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer riskScore) { this.riskScore = riskScore; }

    public String getRiskDecision() { return riskDecision; }
    public void setRiskDecision(String riskDecision) { this.riskDecision = riskDecision; }

    public String getTriggeredRules() { return triggeredRules; }
    public void setTriggeredRules(String triggeredRules) { this.triggeredRules = triggeredRules; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

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