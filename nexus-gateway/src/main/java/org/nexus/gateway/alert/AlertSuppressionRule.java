package org.nexus.gateway.alert;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 告警抑制规则实体。
 *
 * <p>定义父子告警规则之间的抑制关系：当父告警（parentRuleName）激活时，
 * 自动抑制子告警（childRuleName），在抑制持续时间（suppressDuration）内
 * 不再触发子告警通知。抑制超时后自动解除。</p>
 *
 * <p>典型场景：数据库连接故障（父告警）激活时，抑制所有依赖数据库的服务告警（子告警），
 * 避免告警风暴。</p>
 */
@Entity
@Table(name = "alert_suppression_rules")
public class AlertSuppressionRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 父告警规则名称（触发抑制的规则）。 */
    @Column(name = "parent_rule_name", nullable = false, length = 128)
    private String parentRuleName;

    /** 子告警规则名称（被抑制的规则）。 */
    @Column(name = "child_rule_name", nullable = false, length = 128)
    private String childRuleName;

    /** 抑制持续时间（分钟），超过此时间后自动解除抑制。 */
    @Column(name = "suppress_duration_minutes", nullable = false)
    private int suppressDurationMinutes = 30;

    /** 是否启用此抑制规则。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

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

    public String getParentRuleName() {
        return parentRuleName;
    }

    public void setParentRuleName(String parentRuleName) {
        this.parentRuleName = parentRuleName;
    }

    public String getChildRuleName() {
        return childRuleName;
    }

    public void setChildRuleName(String childRuleName) {
        this.childRuleName = childRuleName;
    }

    public int getSuppressDurationMinutes() {
        return suppressDurationMinutes;
    }

    public void setSuppressDurationMinutes(int suppressDurationMinutes) {
        this.suppressDurationMinutes = suppressDurationMinutes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
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