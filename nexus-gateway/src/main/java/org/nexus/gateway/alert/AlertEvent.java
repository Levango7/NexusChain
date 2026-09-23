package org.nexus.gateway.alert;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 告警事件记录。
 *
 * <p>每次 {@link AlertEngine} 检测到指标满足 {@link AlertRule} 条件时，
 * 创建一条 AlertEvent 并通过 {@link AlertNotifier} 发送通知。
 * 事件可通过 {@code AlertController} 的 resolve 端点标记为已解决。</p>
 */
@Entity
@Table(name = "alert_events")
public class AlertEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 触发此事件的规则名称。 */
    @Column(name = "rule_name", nullable = false, length = 128)
    private String ruleName;

    /** 触发此事件的指标名。 */
    @Column(name = "metric_name", nullable = false, length = 128)
    private String metricName;

    /** 触发时的指标当前值。 */
    @Column(name = "current_value", nullable = false)
    private double currentValue;

    /** 规则配置的阈值。 */
    @Column(name = "threshold", nullable = false)
    private double threshold;

    /** 严重级别（从规则继承）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private AlertRule.Severity severity;

    /** 告警消息描述。 */
    @Column(name = "message", length = 512)
    private String message;

    /** 事件触发时间。 */
    @Column(name = "timestamp", nullable = false, updatable = false)
    private LocalDateTime timestamp;

    /** 是否已解决。 */
    @Column(name = "resolved", nullable = false)
    private boolean resolved = false;

    /** 解决时间。 */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    void prePersist() {
        if (this.timestamp == null) {
            this.timestamp = LocalDateTime.now();
        }
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

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public double getCurrentValue() {
        return currentValue;
    }

    public void setCurrentValue(double currentValue) {
        this.currentValue = currentValue;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public AlertRule.Severity getSeverity() {
        return severity;
    }

    public void setSeverity(AlertRule.Severity severity) {
        this.severity = severity;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    public boolean isResolved() {
        return resolved;
    }

    public void setResolved(boolean resolved) {
        this.resolved = resolved;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(LocalDateTime resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}