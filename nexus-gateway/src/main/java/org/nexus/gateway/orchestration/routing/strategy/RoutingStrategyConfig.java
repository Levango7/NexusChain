package org.nexus.gateway.orchestration.routing.strategy;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 多目标路由策略配置 JPA 实体（Wave 16 模块一）。
 *
 * <p>对应表 {@code routing_strategy_configs}（V84）。每条配置定义一组目标权重
 * （成本/成功率/延迟/风险）与适用条件；{@code MultiObjectiveRoutingService} 按
 * priority 降序取第一条条件命中的配置作为生效权重，未命中时回退到
 * {@code nexus.routing.strategy} 配置的默认权重。</p>
 *
 * <p>权重 JSON 格式（{@code objective_weights_json}）：
 * {@code {"COST":0.25,"SUCCESS_RATE":0.35,"LATENCY":0.25,"RISK":0.15}}，
 * 解析与归一化见 {@link MultiObjectiveRoutingService}。</p>
 *
 * <p>条件 JSON（{@code conditions_json}）复用 {@code RoutingRule.conditions} 语义：
 * 支持 {@code currency} / {@code amount_gte} / {@code amount_lte} 键（分单位 long）。</p>
 */
@Entity
@Table(name = "routing_strategy_configs")
public class RoutingStrategyConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", length = 128, nullable = false, unique = true)
    private String name;

    @Column(name = "objective_weights_json", length = 512, nullable = false)
    private String objectiveWeightsJson;

    @Column(name = "conditions_json", length = 1024)
    private String conditionsJson;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "created_by", length = 64)
    private String createdBy;

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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getObjectiveWeightsJson() { return objectiveWeightsJson; }
    public void setObjectiveWeightsJson(String objectiveWeightsJson) { this.objectiveWeightsJson = objectiveWeightsJson; }

    public String getConditionsJson() { return conditionsJson; }
    public void setConditionsJson(String conditionsJson) { this.conditionsJson = conditionsJson; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
