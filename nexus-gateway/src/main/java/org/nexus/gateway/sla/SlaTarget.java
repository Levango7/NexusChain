package org.nexus.gateway.sla;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * SLA 目标定义实体。
 *
 * <p>定义 SLA/SLO 监控的目标指标，包括可用性、延迟、吞吐量和错误率等类型。
 * 每个 SlaTarget 关联一个 Micrometer 指标名称，并设定目标值和统计窗口。</p>
 */
@Entity
@Table(name = "sla_targets")
public class SlaTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SLA 目标名称，如 "Payment API Availability" */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 关联的 Micrometer 指标名称，如 "nexus.payments.confirmed" */
    @Column(name = "metric_name", nullable = false, length = 128)
    private String metricName;

    /** 目标值，如 99.9 表示 99.9% 可用性，或 5000 表示 P99 延迟 5000ms */
    @Column(name = "target_value", nullable = false)
    private double targetValue;

    /** 目标类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 32)
    private TargetType targetType;

    /** 统计窗口（分钟），如 60 表示 1 小时 */
    @Column(name = "window_minutes", nullable = false)
    private int windowMinutes;

    /** 是否启用此 SLA 目标 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 描述信息 */
    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * SLA 目标类型枚举。
     */
    public enum TargetType {
        /** 可用性：成功请求数 / 总请求数 * 100 */
        AVAILABILITY,
        /** 延迟：P99/P95/P50 延迟值（毫秒） */
        LATENCY,
        /** 吞吐量：每秒请求数 */
        THROUGHPUT,
        /** 错误率：错误数 / 总数 * 100 */
        ERROR_RATE
    }

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
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

    public double getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(double targetValue) {
        this.targetValue = targetValue;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(TargetType targetType) {
        this.targetType = targetType;
    }

    public int getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(int windowMinutes) {
        this.windowMinutes = windowMinutes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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
}