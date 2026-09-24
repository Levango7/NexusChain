package org.nexus.gateway.alert;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 告警聚合记录实体。
 *
 * <p>将同一规则名 + 同一严重级别 + 时间窗口（默认5分钟）内的多个告警事件
 * 合并为一条聚合记录，减少告警噪音。</p>
 *
 * <p>聚合键（aggregationKey）格式：{@code ruleName:severity:windowStart}，
 * 用于唯一标识一个聚合组。</p>
 */
@Entity
@Table(name = "alert_aggregations")
public class AlertAggregation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 聚合键，格式为 ruleName:severity:windowStart，唯一标识一个聚合组。 */
    @Column(name = "aggregation_key", unique = true, nullable = false, length = 256)
    private String aggregationKey;

    /** 规则名称。 */
    @Column(name = "rule_name", nullable = false, length = 128)
    private String ruleName;

    /** 严重级别。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private AlertRule.Severity severity;

    /** 聚合的告警事件数量。 */
    @Column(name = "count", nullable = false)
    private int count = 0;

    /** 聚合窗口内第一个事件的 ID。 */
    @Column(name = "first_event_id")
    private Long firstEventId;

    /** 聚合窗口内最后一个事件的 ID。 */
    @Column(name = "last_event_id")
    private Long lastEventId;

    /** 聚合窗口开始时间。 */
    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    /** 聚合窗口结束时间。 */
    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    /** 聚合记录创建/更新时间。 */
    @Column(name = "aggregated_at", nullable = false)
    private LocalDateTime aggregatedAt;

    /** 是否已通知（聚合告警是否已发送通知）。 */
    @Column(name = "notified", nullable = false)
    private boolean notified = false;

    @PrePersist
    void prePersist() {
        if (this.aggregatedAt == null) {
            this.aggregatedAt = LocalDateTime.now();
        }
    }

    // === Getters / Setters ===

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAggregationKey() {
        return aggregationKey;
    }

    public void setAggregationKey(String aggregationKey) {
        this.aggregationKey = aggregationKey;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public AlertRule.Severity getSeverity() {
        return severity;
    }

    public void setSeverity(AlertRule.Severity severity) {
        this.severity = severity;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public Long getFirstEventId() {
        return firstEventId;
    }

    public void setFirstEventId(Long firstEventId) {
        this.firstEventId = firstEventId;
    }

    public Long getLastEventId() {
        return lastEventId;
    }

    public void setLastEventId(Long lastEventId) {
        this.lastEventId = lastEventId;
    }

    public LocalDateTime getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(LocalDateTime windowStart) {
        this.windowStart = windowStart;
    }

    public LocalDateTime getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(LocalDateTime windowEnd) {
        this.windowEnd = windowEnd;
    }

    public LocalDateTime getAggregatedAt() {
        return aggregatedAt;
    }

    public void setAggregatedAt(LocalDateTime aggregatedAt) {
        this.aggregatedAt = aggregatedAt;
    }

    public boolean isNotified() {
        return notified;
    }

    public void setNotified(boolean notified) {
        this.notified = notified;
    }
}