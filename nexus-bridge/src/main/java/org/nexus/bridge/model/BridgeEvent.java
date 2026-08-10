package org.nexus.bridge.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 桥事件模型，记录跨链操作过程中产生的关键事件。
 *
 * <p>桥事件通过 nexus-core 的事件总线进行传播和持久化，
 * 外部系统可订阅事件实现跨链通知、对账、监控等功能。</p>
 *
 * <h2>事件类型</h2>
 * <ul>
 *   <li>{@code LOCK_INITIATED} — 锁定请求已接收</li>
 *   <li>{@code LOCK_CONFIRMED} — 源链锁定交易已确认</li>
 *   <li>{@code MINT_INITIATED} — 铸造请求已提交</li>
 *   <li>{@code MINT_CONFIRMED} — 目标链铸造交易已确认</li>
 *   <li>{@code BURN_INITIATED} — 销毁请求已接收</li>
 *   <li>{@code BURN_CONFIRMED} — 目标链销毁交易已确认</li>
 *   <li>{@code UNLOCK_INITIATED} — 解锁请求已提交</li>
 *   <li>{@code UNLOCK_CONFIRMED} — 原链解锁交易已确认</li>
 *   <li>{@code BRIDGE_PAUSED} — 桥已暂停</li>
 *   <li>{@code BRIDGE_RESUMED} — 桥已恢复</li>
 *   <li>{@code BRIDGE_EMERGENCY_STOP} — 桥紧急停止</li>
 *   <li>{@code VALIDATOR_ADDED} — 新验证者加入</li>
 *   <li>{@code VALIDATOR_REMOVED} — 验证者移除</li>
 *   <li>{@code THRESHOLD_CHANGED} — 签名阈值变更</li>
 * </ul>
 *
 * @since 1.0.0
 */
public class BridgeEvent {

    /** 事件唯一 ID。 */
    private String eventId;

    /** 关联的桥交易 ID（可为空）。 */
    private String txId;

    /** 事件类型。 */
    private EventType eventType;

    /** 源链 ID。 */
    private String sourceChainId;

    /** 目标链 ID。 */
    private String targetChainId;

    /** 事件关联金额（NEX 最小单位，可为 0）。 */
    private long amount;

    /** 事件触发者（验证者 ID 或用户地址）。 */
    private String actor;

    /** 事件描述信息。 */
    private String description;

    /** 事件发生时间。 */
    private Instant timestamp;

    /** 事件附加数据（JSON 格式）。 */
    private String data;

    /**
     * 事件类型枚举。
     */
    public enum EventType {
        // 锁定相关
        LOCK_INITIATED,
        LOCK_CONFIRMED,
        // 铸造相关
        MINT_INITIATED,
        MINT_CONFIRMED,
        // 销毁相关
        BURN_INITIATED,
        BURN_CONFIRMED,
        // 解锁相关
        UNLOCK_INITIATED,
        UNLOCK_CONFIRMED,
        // 桥状态变更
        BRIDGE_PAUSED,
        BRIDGE_RESUMED,
        BRIDGE_EMERGENCY_STOP,
        // 治理事件
        VALIDATOR_ADDED,
        VALIDATOR_REMOVED,
        THRESHOLD_CHANGED,
        // 异常事件
        TRANSACTION_FAILED,
        TRANSACTION_TIMEOUT,
        DAILY_LIMIT_EXCEEDED
    }

    /**
     * 默认构造函数。
     */
    public BridgeEvent() {
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getTxId() {
        return txId;
    }

    public void setTxId(String txId) {
        this.txId = txId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public void setEventType(EventType eventType) {
        this.eventType = eventType;
    }

    public String getSourceChainId() {
        return sourceChainId;
    }

    public void setSourceChainId(String sourceChainId) {
        this.sourceChainId = sourceChainId;
    }

    public String getTargetChainId() {
        return targetChainId;
    }

    public void setTargetChainId(String targetChainId) {
        this.targetChainId = targetChainId;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BridgeEvent that = (BridgeEvent) o;
        return Objects.equals(eventId, that.eventId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId);
    }

    @Override
    public String toString() {
        return "BridgeEvent{"
                + "eventId='" + eventId + '\''
                + ", eventType=" + eventType
                + ", txId='" + txId + '\''
                + ", sourceChainId='" + sourceChainId + '\''
                + ", targetChainId='" + targetChainId + '\''
                + ", amount=" + amount
                + ", actor='" + actor + '\''
                + ", timestamp=" + timestamp
                + '}';
    }
}
