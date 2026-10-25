package org.nexus.bridge.safety;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 日限额用量单行记录（PLAN-038）。
 *
 * <p>对应迁移 {@code V6__daily_usage_and_audit.sql} 的 {@code bridge_daily_usage}
 * 表（单例行，{@code id} 恒为 1）：滚动窗口起点 + 窗口内已用量。字段与 SQL
 * 必须一致（同 V5/bridge_messages 的模式）。</p>
 *
 * @since 2.76.0
 */
@Entity
@Table(name = "bridge_daily_usage")
public class BridgeDailyUsageRecord {

    /** 单例行主键（恒为 1，见 {@code JdbcDailyUsageStore.SINGLETON_ID}）。 */
    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    /** 滚动窗口起点（epoch millis；过窗后由下次预留滚动）。 */
    @Column(name = "window_start", nullable = false)
    private long windowStart;

    /** 窗口内已用量（NEX 最小单位）。 */
    @Column(name = "used", nullable = false)
    private long used;

    /** 最近更新时刻。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 要求的无参构造。 */
    protected BridgeDailyUsageRecord() {
    }

    public BridgeDailyUsageRecord(Long id, long windowStart, long used, Instant updatedAt) {
        this.id = id;
        this.windowStart = windowStart;
        this.used = used;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public long getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(long windowStart) {
        this.windowStart = windowStart;
    }

    public long getUsed() {
        return used;
    }

    public void setUsed(long used) {
        this.used = used;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
