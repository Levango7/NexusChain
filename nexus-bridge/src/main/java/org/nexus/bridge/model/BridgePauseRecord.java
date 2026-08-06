package org.nexus.bridge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * 桥暂停状态持久化记录。
 *
 * <p>每个桥 ID 对应一条记录，记录当前状态（ACTIVE / PAUSED / EMERGENCY_STOP）
 * 与暂停原因。由 {@code DefaultEmergencyPauseService} 维护。</p>
 *
 * @since 1.2
 */
@Entity
@Table(name = "bridge_pause_records")
public class BridgePauseRecord {

    /** 桥 ID（主键）。 */
    @Id
    @Column(name = "bridge_id", length = 64)
    private String bridgeId;

    /** 桥状态（ACTIVE / PAUSED / EMERGENCY_STOP）。 */
    @Column(name = "state", nullable = false, length = 32)
    private String state;

    /** 暂停原因（状态为 PAUSED / EMERGENCY_STOP 时记录）。 */
    @Column(name = "reason", length = 512)
    private String reason;

    /** 触发暂停的操作者（如验证者 ID）。 */
    @Column(name = "triggered_by", length = 128)
    private String triggeredBy;

    /** 最后更新时间。 */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** 默认构造函数。 */
    public BridgePauseRecord() {
    }

    /**
     * 全参数构造函数。
     *
     * @param bridgeId    桥 ID
     * @param state       状态
     * @param reason      暂停原因
     * @param triggeredBy 触发者
     */
    public BridgePauseRecord(String bridgeId, String state, String reason, String triggeredBy) {
        this.bridgeId = bridgeId;
        this.state = state;
        this.reason = reason;
        this.triggeredBy = triggeredBy;
        this.updatedAt = Instant.now();
    }

    public String getBridgeId() {
        return bridgeId;
    }

    public void setBridgeId(String bridgeId) {
        this.bridgeId = bridgeId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BridgePauseRecord that = (BridgePauseRecord) o;
        return Objects.equals(bridgeId, that.bridgeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bridgeId);
    }

    @Override
    public String toString() {
        return "BridgePauseRecord{"
                + "bridgeId='" + bridgeId + '\''
                + ", state='" + state + '\''
                + ", reason='" + reason + '\''
                + ", updatedAt=" + updatedAt
                + '}';
    }
}