package org.nexus.gateway.orchestration.routing.experiment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 路由实验配置 JPA 实体（Wave 16 模块四）。
 *
 * <p>对应表 {@code routing_experiments}（V88）。对照组/实验组均为 JSON 配置：</p>
 * <ul>
 *   <li>{@code control_group_json}：{@code {"groupId":"control","connectorIds":["chain"],"description":...}}</li>
 *   <li>{@code experiment_groups_json}：
 *       {@code [{"groupId":"g1","weight":30,"connectorIds":["mock"],"description":...},...]}；
 *       各组 weight 之和 ≤ 100，其余流量归属对照组。</li>
 * </ul>
 *
 * <p>状态机：CREATED → RUNNING ⇄ PAUSED → COMPLETED / TERMINATED（终态）。
 * 分流与显著性评估见 {@link RoutingExperimentService}。</p>
 */
@Entity
@Table(name = "routing_experiments")
public class RoutingExperiment {

    /** 实验状态。 */
    public enum Status { CREATED, RUNNING, PAUSED, COMPLETED, TERMINATED }

    /** 目标指标。 */
    public enum TargetMetric { SUCCESS_RATE, LATENCY, COST }

    @Id
    @Column(name = "experiment_id", length = 64, nullable = false)
    private String experimentId;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "status", length = 16, nullable = false)
    private String status = Status.CREATED.name();

    @Column(name = "control_group_json", length = 1024, nullable = false)
    private String controlGroupJson;

    @Column(name = "experiment_groups_json", length = 2048, nullable = false)
    private String experimentGroupsJson;

    @Column(name = "target_metric", length = 32, nullable = false)
    private String targetMetric = TargetMetric.SUCCESS_RATE.name();

    @Column(name = "significance_threshold", nullable = false)
    private double significanceThreshold = 0.05;

    @Column(name = "min_sample_size", nullable = false)
    private int minSampleSize = 1000;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    // === Getters & Setters ===

    public String getExperimentId() { return experimentId; }
    public void setExperimentId(String experimentId) { this.experimentId = experimentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getControlGroupJson() { return controlGroupJson; }
    public void setControlGroupJson(String controlGroupJson) { this.controlGroupJson = controlGroupJson; }

    public String getExperimentGroupsJson() { return experimentGroupsJson; }
    public void setExperimentGroupsJson(String experimentGroupsJson) { this.experimentGroupsJson = experimentGroupsJson; }

    public String getTargetMetric() { return targetMetric; }
    public void setTargetMetric(String targetMetric) { this.targetMetric = targetMetric; }

    public double getSignificanceThreshold() { return significanceThreshold; }
    public void setSignificanceThreshold(double significanceThreshold) { this.significanceThreshold = significanceThreshold; }

    public int getMinSampleSize() { return minSampleSize; }
    public void setMinSampleSize(int minSampleSize) { this.minSampleSize = minSampleSize; }

    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }

    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
