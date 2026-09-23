package org.nexus.gateway.alert;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 告警规则定义实体。
 *
 * <p>每条规则绑定一个 Micrometer 指标名（如 {@code nexus.payments.failed}），
 * 定义比较条件（GT/LT/GTE/LTE/EQ）与阈值。{@link AlertEngine} 定时扫描
 * 所有 enabled 的规则，从 {@link io.micrometer.core.instrument.MeterRegistry}
 * 读取指标当前值并与阈值比较，满足条件时触发 {@link AlertEvent}。</p>
 *
 * <p>冷却机制：同一规则在 {@code cooldownMinutes} 内不重复触发，
 * 避免告警风暴。</p>
 */
@Entity
@Table(name = "alert_rules")
public class AlertRule {

    /**
     * 比较条件枚举。
     * <ul>
     *   <li>GT — 大于阈值</li>
     *   <li>LT — 小于阈值</li>
     *   <li>GTE — 大于等于阈值</li>
     *   <li>LTE — 小于等于阈值</li>
     *   <li>EQ — 等于阈值</li>
     * </ul>
     */
    public enum Condition {
        GT, LT, GTE, LTE, EQ
    }

    /**
     * 告警严重级别。
     * <ul>
     *   <li>INFO — 信息级，仅需关注</li>
     *   <li>WARN — 警告级，需要排查</li>
     *   <li>CRITICAL — 严重级，需立即处理</li>
     * </ul>
     */
    public enum Severity {
        INFO, WARN, CRITICAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则名称，唯一标识一条告警规则。 */
    @Column(name = "name", unique = true, nullable = false, length = 128)
    private String name;

    /** 绑定的 Micrometer 指标名（如 nexus.payments.failed）。 */
    @Column(name = "metric_name", nullable = false, length = 128)
    private String metricName;

    /** 比较条件。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "condition", nullable = false, length = 8)
    private Condition condition;

    /** 阈值，指标值与此值比较。 */
    @Column(name = "threshold", nullable = false)
    private double threshold;

    /** 时间窗口（分钟），引擎在此窗口内聚合指标值。 */
    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes = 5;

    /** 告警冷却期（分钟），同一规则在此期间内不重复触发。 */
    @Column(name = "cooldown_minutes", nullable = false)
    private int cooldownMinutes = 10;

    /** 是否启用此规则。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 严重级别。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private Severity severity = Severity.WARN;

    /** 规则描述。 */
    @Column(name = "description", length = 512)
    private String description;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMetricName() {
        return metricName;
    }

    public void setMetricName(String metricName) {
        this.metricName = metricName;
    }

    public Condition getCondition() {
        return condition;
    }

    public void setCondition(Condition condition) {
        this.condition = condition;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(int windowMinutes) {
        this.windowMinutes = windowMinutes;
    }

    public int getCooldownMinutes() {
        return cooldownMinutes;
    }

    public void setCooldownMinutes(int cooldownMinutes) {
        this.cooldownMinutes = cooldownMinutes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    /**
     * 判断给定值是否满足此规则的告警条件。
     *
     * @param value 当前指标值
     * @return true 表示满足条件，应触发告警
     */
    public boolean matches(double value) {
        return switch (condition) {
            case GT -> value > threshold;
            case LT -> value < threshold;
            case GTE -> value >= threshold;
            case LTE -> value <= threshold;
            case EQ -> Double.compare(value, threshold) == 0;
        };
    }
}