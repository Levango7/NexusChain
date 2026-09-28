package org.nexus.gateway.orchestration.routing.profile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 商户路由策略配置 JPA 实体（Wave 16 模块六）。
 *
 * <p>对应表 {@code merchant_routing_profiles}（V90）。支持商户级（merchant_id 非空）
 * 与行业级（merchant_id 为空 + industry 字段）两层配置，priority 大者优先。</p>
 *
 * <p>JSON 列语义：</p>
 * <ul>
 *   <li>{@code amount_tier_rules_json}：
 *       {@code [{"min":0,"max":10000,"tier":"SMALL"},...]}（分，闭开区间 [min,max)），
 *       未配置时回退 {@code nexus.routing.profile.*-amount-threshold}；</li>
 *   <li>{@code time_window_rules_json}：
 *       {@code [{"start":"09:00","end":"22:00","name":"peak"},...]}（本地时区），
 *       未配置时回退全局 peak-hours 配置；</li>
 *   <li>{@code preferred_objectives_json}：{@code ["LATENCY","COST"]}（信息性）；</li>
 *   <li>{@code connector_preferences_json}：
 *       {@code {"preferred":["wechat"],"excluded":["mock"]}}。</li>
 * </ul>
 */
@Entity
@Table(name = "merchant_routing_profiles")
public class MerchantRoutingProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_id", length = 64, nullable = false, unique = true)
    private String profileId;

    /** 商户 ID，NULL = 行业级配置。 */
    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "industry", length = 32)
    private String industry;

    @Column(name = "amount_tier_rules_json", length = 1024)
    private String amountTierRulesJson;

    @Column(name = "time_window_rules_json", length = 1024)
    private String timeWindowRulesJson;

    @Column(name = "preferred_objectives_json", length = 512)
    private String preferredObjectivesJson;

    @Column(name = "connector_preferences_json", length = 1024)
    private String connectorPreferencesJson;

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

    public String getProfileId() { return profileId; }
    public void setProfileId(String profileId) { this.profileId = profileId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }

    public String getAmountTierRulesJson() { return amountTierRulesJson; }
    public void setAmountTierRulesJson(String amountTierRulesJson) { this.amountTierRulesJson = amountTierRulesJson; }

    public String getTimeWindowRulesJson() { return timeWindowRulesJson; }
    public void setTimeWindowRulesJson(String timeWindowRulesJson) { this.timeWindowRulesJson = timeWindowRulesJson; }

    public String getPreferredObjectivesJson() { return preferredObjectivesJson; }
    public void setPreferredObjectivesJson(String preferredObjectivesJson) { this.preferredObjectivesJson = preferredObjectivesJson; }

    public String getConnectorPreferencesJson() { return connectorPreferencesJson; }
    public void setConnectorPreferencesJson(String connectorPreferencesJson) { this.connectorPreferencesJson = connectorPreferencesJson; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
