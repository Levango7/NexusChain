package org.nexus.bridge.safety;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 日限额历史快照（PLAN-038）。
 *
 * <p>对应迁移 {@code V6__daily_usage_and_audit.sql} 的
 * {@code bridge_daily_limit_snapshot} 表：按日期键（{@code yyyy-MM-dd}，
 * Asia/Shanghai，与 {@code DailyLimitAuditService} 口径一致）记录当日已用量
 * 快照，供桥运营审计历史吞吐。字段与 SQL 必须一致（同 V5 模式）。</p>
 *
 * @since 2.76.0
 */
@Entity
@Table(name = "bridge_daily_limit_snapshot")
public class BridgeDailyLimitSnapshot {

    /** 日期键（yyyy-MM-dd，Asia/Shanghai）。 */
    @Id
    @Column(name = "date_key", length = 16, nullable = false)
    private String dateKey;

    /** 该日快照的已用量（NEX 最小单位）。 */
    @Column(name = "used", nullable = false)
    private long used;

    /** 记录时刻。 */
    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    /** JPA 要求的无参构造。 */
    protected BridgeDailyLimitSnapshot() {
    }

    public BridgeDailyLimitSnapshot(String dateKey, long used, Instant capturedAt) {
        this.dateKey = dateKey;
        this.used = used;
        this.capturedAt = capturedAt;
    }

    public String getDateKey() {
        return dateKey;
    }

    public long getUsed() {
        return used;
    }

    public void setUsed(long used) {
        this.used = used;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }
}
