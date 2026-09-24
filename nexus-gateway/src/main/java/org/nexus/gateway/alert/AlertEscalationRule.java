package org.nexus.gateway.alert;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 告警升级规则实体。
 *
 * <p>定义告警升级策略：当告警在指定时间（escalateAfterMinutes）内未解决时，
 * 自动将严重级别从 fromSeverity 升级到 toSeverity，并通过 escalationChannel
 * 发送升级通知。</p>
 *
 * <p>典型场景：WARN 级别的告警在 30 分钟内未解决，自动升级为 CRITICAL 并通知紧急渠道。</p>
 */
@Entity
@Table(name = "alert_escalation_rules")
public class AlertEscalationRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则名称，唯一标识一条升级规则。 */
    @Column(name = "rule_name", unique = true, nullable = false, length = 128)
    private String ruleName;

    /** 升级触发时间（分钟），告警在此时间内未解决则触发升级。 */
    @Column(name = "escalate_after_minutes", nullable = false)
    private int escalateAfterMinutes = 30;

    /** 原始严重级别（满足此级别才触发升级）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_severity", nullable = false, length = 16)
    private AlertRule.Severity fromSeverity;

    /** 升级后的严重级别。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "to_severity", nullable = false, length = 16)
    private AlertRule.Severity toSeverity;

    /** 升级通知渠道（如 "webhook", "email", "sms" 等）。 */
    @Column(name = "escalation_channel", length = 64)
    private String escalationChannel;

    /** 是否启用此升级规则。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // === Getters / Setters ===

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public int getEscalateAfterMinutes() {
        return escalateAfterMinutes;
    }

    public void setEscalateAfterMinutes(int escalateAfterMinutes) {
        this.escalateAfterMinutes = escalateAfterMinutes;
    }

    public AlertRule.Severity getFromSeverity() {
        return fromSeverity;
    }

    public void setFromSeverity(AlertRule.Severity fromSeverity) {
        this.fromSeverity = fromSeverity;
    }

    public AlertRule.Severity getToSeverity() {
        return toSeverity;
    }

    public void setToSeverity(AlertRule.Severity toSeverity) {
        this.toSeverity = toSeverity;
    }

    public String getEscalationChannel() {
        return escalationChannel;
    }

    public void setEscalationChannel(String escalationChannel) {
        this.escalationChannel = escalationChannel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}