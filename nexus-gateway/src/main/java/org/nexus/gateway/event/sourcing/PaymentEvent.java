package org.nexus.gateway.event.sourcing;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/**
 * 支付事件基类（事件溯源模式）。
 *
 * <p>所有支付领域事件均继承自本基类。每个事件携带以下元信息：
 * <ul>
 *   <li>{@code eventId}：事件唯一标识（UUID），用于幂等去重</li>
 *   <li>{@code aggregateId}：聚合根 ID（即支付订单 ID），用于事件分组与重放</li>
 *   <li>{@code eventType}：事件类型标识，用于路由与反序列化</li>
 *   <li>{@code timestamp}：事件发生时间（UTC Instant）</li>
 *   <li>{@code version}：聚合根版本号（乐观锁，事件追加时递增）</li>
 * </ul>
 *
 * <p>本类与 {@code org.nexus.gateway.event.PaymentEvent}（Spring ApplicationEvent）
 * 共存：前者用于事件溯源持久化与重放，后者用于进程内异步通知。两者不互相继承，
 * 避免将 Spring 容器依赖耦合进事件存储层。
 *
 * <p>实现 {@link Serializable} 以支持 Kafka 序列化与跨服务传输。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
public abstract class PaymentEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 事件唯一标识 */
    private final String eventId;
    /** 聚合根 ID（支付订单 ID 字符串形式，便于跨服务传输） */
    private final String aggregateId;
    /** 事件发生时间（UTC） */
    private final Instant timestamp;
    /** 聚合根版本号（事件追加时的目标版本，乐观锁控制并发） */
    private final long version;

    protected PaymentEvent(String aggregateId, long version) {
        this.eventId = UUID.randomUUID().toString();
        this.aggregateId = aggregateId;
        this.timestamp = Instant.now();
        this.version = version;
    }

    protected PaymentEvent(String eventId, String aggregateId, Instant timestamp, long version) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.timestamp = timestamp;
        this.version = version;
    }

    /** 事件类型标识，由子类提供（如 "PAYMENT_CREATED"）。 */
    public abstract String getEventType();

    public String getEventId() {
        return eventId;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "eventId='" + eventId + '\'' +
                ", aggregateId='" + aggregateId + '\'' +
                ", eventType='" + getEventType() + '\'' +
                ", timestamp=" + timestamp +
                ", version=" + version +
                '}';
    }
}