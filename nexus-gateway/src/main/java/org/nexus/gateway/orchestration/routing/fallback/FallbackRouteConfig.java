package org.nexus.gateway.orchestration.routing.fallback;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 商户/渠道级降级路由配置 JPA 实体（Wave 16 模块三）。
 *
 * <p>对应表 {@code fallback_route_configs}（V86）。定义「主 connector 失败/不可用时
 * 按序尝试的备选 connector 列表」，支持商户级（merchant_id 非空）与全局/渠道级
 * （merchant_id 为空）两层，priority 大者优先；金额/币种条件复用
 * {@code conditions_json}（currency / amount_gte / amount_lte，分）。</p>
 */
@Entity
@Table(name = "fallback_route_configs")
public class FallbackRouteConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL = 全局/渠道级配置。 */
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "primary_connector", length = 64, nullable = false)
    private String primaryConnector;

    /** 有序备选，逗号分隔。 */
    @Column(name = "fallback_connectors_csv", length = 512, nullable = false)
    private String fallbackConnectorsCsv;

    @Column(name = "conditions_json", length = 1024)
    private String conditionsJson;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

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

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getPrimaryConnector() { return primaryConnector; }
    public void setPrimaryConnector(String primaryConnector) { this.primaryConnector = primaryConnector; }

    public String getFallbackConnectorsCsv() { return fallbackConnectorsCsv; }
    public void setFallbackConnectorsCsv(String fallbackConnectorsCsv) { this.fallbackConnectorsCsv = fallbackConnectorsCsv; }

    public String getConditionsJson() { return conditionsJson; }
    public void setConditionsJson(String conditionsJson) { this.conditionsJson = conditionsJson; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
