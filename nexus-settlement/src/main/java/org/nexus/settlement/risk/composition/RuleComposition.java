package org.nexus.settlement.risk.composition;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 规则组合实体 — 定义规则之间的组合逻辑、优先级和处置动作映射。
 *
 * <p>支持 AND/OR 组合表达式，将多条规则组合为一个复合判断单元：
 * <ul>
 *   <li>AND 逻辑：所有规则都命中时才触发处置动作</li>
 *   <li>OR 逻辑：任意规则命中即触发处置动作</li>
 * </ul>
 * </p>
 *
 * <p>组合表达式格式示例：
 * <ul>
 *   <li>AND: "IP_SCORE & REGION_SCORE" — IP 和地域都高风险时才阻断</li>
 *   <li>OR: "IP_SCORE | BLACKLIST_SCORE" — IP 或黑名单任一命中即阻断</li>
 *   <li>复合: "(IP_SCORE & TIME_SCORE) | BLACKLIST_SCORE"</li>
 * </ul>
 * </p>
 */
@Entity
@Table(name = "rule_compositions")
public class RuleComposition {

    /** 组合逻辑类型 */
    public enum CompositionType {
        /** AND 逻辑：所有规则都命中才触发 */
        AND,
        /** OR 逻辑：任意规则命中即触发 */
        OR
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

    /** 组合名称 */
    @Column(name = "composition_name", nullable = false, unique = true, length = 128)
    private String compositionName;

    /** 组合逻辑类型（AND/OR） */
    @Enumerated(EnumType.STRING)
    @Column(name = "composition_type", nullable = false, length = 16)
    private CompositionType compositionType;

    /** 组合表达式（如 "IP_SCORE & REGION_SCORE"） */
    @Column(name = "expression", nullable = false, length = 1024)
    private String expression;

    /** 优先级（数字越小优先级越高） */
    @Column(name = "priority", nullable = false)
    private Integer priority = 50;

    /** 处置动作 */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 32)
    private ActionType action;

    /** 是否启用 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 评分阈值（组合触发时所需的最小评分） */
    @Column(name = "score_threshold")
    private Integer scoreThreshold;

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

    public String getCompositionName() { return compositionName; }
    public void setCompositionName(String compositionName) { this.compositionName = compositionName; }

    public CompositionType getCompositionType() { return compositionType; }
    public void setCompositionType(CompositionType compositionType) { this.compositionType = compositionType; }

    public String getExpression() { return expression; }
    public void setExpression(String expression) { this.expression = expression; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public ActionType getAction() { return action; }
    public void setAction(ActionType action) { this.action = action; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Integer getScoreThreshold() { return scoreThreshold; }
    public void setScoreThreshold(Integer scoreThreshold) { this.scoreThreshold = scoreThreshold; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}