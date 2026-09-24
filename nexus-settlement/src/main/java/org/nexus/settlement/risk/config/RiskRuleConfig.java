package org.nexus.settlement.risk.config;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 风控规则配置实体 — DB 驱动的规则配置管理。
 *
 * <p>支持动态启用/禁用规则、配置阈值和处置动作，无需重启即可调整风控策略。
 * configJson 字段存储规则特定的扩展配置（如黑名单列表、地区列表等）。</p>
 */
@Entity
@Table(name = "risk_rule_configs")
public class RiskRuleConfig {

    /** 规则类型枚举 */
    public enum RuleType {
        /** 评分规则 */
        SCORING,
        /** 拦截规则 */
        BLOCKING,
        /** 组合规则 */
        COMPOSITE
    }

    /** 处置动作枚举 */
    public enum ActionType {
        /** 告警 */
        ALERT,
        /** 阻断 */
        BLOCK,
        /** 人工审核 */
        MANUAL_REVIEW,
        /** 捕获并放行 */
        CAPTURE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则名称（唯一） */
    @Column(name = "rule_name", nullable = false, unique = true, length = 128)
    private String ruleName;

    /** 规则类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 32)
    private RuleType ruleType;

    /** 是否启用 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 优先级（数字越小优先级越高） */
    @Column(name = "priority", nullable = false)
    private Integer priority = 50;

    /** 阈值（评分阈值或拦截阈值） */
    @Column(name = "threshold")
    private Integer threshold;

    /** 处置动作 */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 32)
    private ActionType action;

    /** 扩展配置（JSON 格式，存储规则特定参数） */
    @Column(name = "config_json", length = 2048)
    private String configJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public RuleType getRuleType() { return ruleType; }
    public void setRuleType(RuleType ruleType) { this.ruleType = ruleType; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public Integer getThreshold() { return threshold; }
    public void setThreshold(Integer threshold) { this.threshold = threshold; }

    public ActionType getAction() { return action; }
    public void setAction(ActionType action) { this.action = action; }

    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}