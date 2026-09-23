package org.nexus.gateway.sla;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * SLA 测量记录实体。
 *
 * <p>每次 SLA 监控检查时生成的测量结果，记录实际指标值、目标值、是否达标，
 * 以及测量的时间窗口范围。用于历史追踪和报告生成。</p>
 */
@Entity
@Table(name = "sla_measurements")
public class SlaMeasurement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的 SlaTarget ID */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** 实际测量值 */
    @Column(name = "measured_value", nullable = false)
    private double measuredValue;

    /** 目标值（冗余存储，便于历史查询时无需 JOIN） */
    @Column(name = "target_value", nullable = false)
    private double targetValue;

    /** 是否达标 */
    @Column(name = "is_met", nullable = false)
    private boolean isMet;

    /** 测量时间 */
    @Column(name = "measured_at", nullable = false)
    private LocalDateTime measuredAt;

    /** 统计窗口起始时间 */
    @Column(name = "window_start", nullable = false)
    private LocalDateTime windowStart;

    /** 统计窗口结束时间 */
    @Column(name = "window_end", nullable = false)
    private LocalDateTime windowEnd;

    @PrePersist
    void prePersist() {
        if (this.measuredAt == null) {
            this.measuredAt = LocalDateTime.now();
        }
    }

    // === Getters / Setters ===

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTargetId() {
        return targetId;
    }

    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    public double getMeasuredValue() {
        return measuredValue;
    }

    public void setMeasuredValue(double measuredValue) {
        this.measuredValue = measuredValue;
    }

    public double getTargetValue() {
        return targetValue;
    }

    public void setTargetValue(double targetValue) {
        this.targetValue = targetValue;
    }

    public boolean isMet() {
        return isMet;
    }

    public void setMet(boolean met) {
        isMet = met;
    }

    public LocalDateTime getMeasuredAt() {
        return measuredAt;
    }

    public void setMeasuredAt(LocalDateTime measuredAt) {
        this.measuredAt = measuredAt;
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
}