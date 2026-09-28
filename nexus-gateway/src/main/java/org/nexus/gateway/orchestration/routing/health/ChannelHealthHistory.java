package org.nexus.gateway.orchestration.routing.health;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 渠道健康度历史记录 JPA 实体（Wave 16 模块二）。
 *
 * <p>对应表 {@code channel_health_history}（V85）。由
 * {@link ChannelHealthService} 定时采样写入，用于回溯渠道健康趋势与
 * 多目标路由的 risk 维度评分。</p>
 */
@Entity
@Table(name = "channel_health_history")
public class ChannelHealthHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connector_id", length = 64, nullable = false)
    private String connectorId;

    /** 综合健康度 [0,1]。 */
    @Column(name = "overall_score", nullable = false)
    private double overallScore;

    @Column(name = "success_rate_score", nullable = false)
    private double successRateScore;

    @Column(name = "latency_score", nullable = false)
    private double latencyScore;

    @Column(name = "error_rate_score", nullable = false)
    private double errorRateScore;

    @Column(name = "capacity_score", nullable = false)
    private double capacityScore;

    /** HEALTHY / DEGRADED / UNHEALTHY。 */
    @Column(name = "level", length = 16, nullable = false)
    private String level;

    @Column(name = "sampled_at", updatable = false)
    private LocalDateTime sampledAt;

    @PrePersist
    void prePersist() {
        if (this.sampledAt == null) {
            this.sampledAt = LocalDateTime.now();
        }
    }

    // === Getters & Setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }

    public double getOverallScore() { return overallScore; }
    public void setOverallScore(double overallScore) { this.overallScore = overallScore; }

    public double getSuccessRateScore() { return successRateScore; }
    public void setSuccessRateScore(double successRateScore) { this.successRateScore = successRateScore; }

    public double getLatencyScore() { return latencyScore; }
    public void setLatencyScore(double latencyScore) { this.latencyScore = latencyScore; }

    public double getErrorRateScore() { return errorRateScore; }
    public void setErrorRateScore(double errorRateScore) { this.errorRateScore = errorRateScore; }

    public double getCapacityScore() { return capacityScore; }
    public void setCapacityScore(double capacityScore) { this.capacityScore = capacityScore; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public LocalDateTime getSampledAt() { return sampledAt; }
    public void setSampledAt(LocalDateTime sampledAt) { this.sampledAt = sampledAt; }
}
