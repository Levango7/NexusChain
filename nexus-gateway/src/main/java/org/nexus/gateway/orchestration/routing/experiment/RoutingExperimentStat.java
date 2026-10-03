package org.nexus.gateway.orchestration.routing.experiment;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 路由实验组统计 JPA 实体（Wave 16 模块四边界收尾，V91）。
 *
 * <p>承载 {@code RoutingExperimentService.recordOutcome} 的 write-through 累加：
 * 每条实验结果事件在更新进程内计数器的同时落一行累加记录；服务启动时全量回灌为
 * 进程内基线——重启/部署不再清零 A/B 实验的样本积累。</p>
 *
 * <p>并发口径：累加经数据库原子 {@code UPDATE ... SET x = x + :n}（见
 * {@link RoutingExperimentStatRepository#accumulate}），行不存在时插入；
 * 同组并发事件的丢失窗口已被原子更新语义排除。</p>
 */
@Entity
@Table(name = "routing_experiment_stats",
        uniqueConstraints = @UniqueConstraint(name = "uk_res_exp_group",
                columnNames = {"experiment_id", "group_id"}))
public class RoutingExperimentStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_id", length = 64, nullable = false)
    private String experimentId;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "event_count", nullable = false)
    private long eventCount;

    @Column(name = "success_count", nullable = false)
    private long successCount;

    @Column(name = "total_latency_ms", nullable = false)
    private long totalLatencyMs;

    @Column(name = "total_cost_bps", nullable = false)
    private long totalCostBps;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // === Getters & Setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getExperimentId() { return experimentId; }
    public void setExperimentId(String experimentId) { this.experimentId = experimentId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public long getEventCount() { return eventCount; }
    public void setEventCount(long eventCount) { this.eventCount = eventCount; }

    public long getSuccessCount() { return successCount; }
    public void setSuccessCount(long successCount) { this.successCount = successCount; }

    public long getTotalLatencyMs() { return totalLatencyMs; }
    public void setTotalLatencyMs(long totalLatencyMs) { this.totalLatencyMs = totalLatencyMs; }

    public long getTotalCostBps() { return totalCostBps; }
    public void setTotalCostBps(long totalCostBps) { this.totalCostBps = totalCostBps; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
