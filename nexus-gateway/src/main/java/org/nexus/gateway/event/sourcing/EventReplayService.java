package org.nexus.gateway.event.sourcing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 事件重放服务。
 *
 * <p>从 {@link EventStore} 读取历史事件，重建 {@link PaymentAggregate} 状态。
 * 用于：
 * <ul>
 *   <li>故障恢复：服务重启后重建聚合根内存状态</li>
 *   <li>读模型重建：CQRS 投影侧从事件流重新构建读模型</li>
 *   <li>历史审计：查询某聚合根在任意历史时刻的状态</li>
 *   <li>调试排查：通过事件序列还原业务流程</li>
 * </ul>
 *
 * <p>本服务与 {@link EventStore} 解耦：
 * <ul>
 *   <li>当 EventStore 为 {@link InMemoryEventStore} 时，直接调用 loadEvents 重放</li>
 *   <li>当 EventStore 为 {@link KafkaEventStore} 时，需配合 Kafka consumer.seek 实现，
 *       本服务提供 {@link #replayFromEvents} 入口接受外部加载的事件序列</li>
 * </ul>
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
@Service
public class EventReplayService {

    private static final Logger log = LoggerFactory.getLogger(EventReplayService.class);

    private final EventStore eventStore;

    public EventReplayService(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    /**
     * 重放指定聚合根的全部历史事件，重建聚合根当前状态。
     *
     * <p>适用于 {@link InMemoryEventStore} 等支持随机读取的 EventStore 实现。
     * 对于 {@link KafkaEventStore}，请使用 {@link #replayFromEvents} 接受外部加载的事件。
     *
     * @param aggregateId 聚合根 ID
     * @return 重放后的聚合根；若 EventStore 不支持直接读取，返回空聚合根并记录 WARN
     */
    public PaymentAggregate replay(String aggregateId) {
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId must not be blank");
        }
        List<PaymentEvent> events = eventStore.loadEvents(aggregateId);
        if (events == null || events.isEmpty()) {
            log.debug("Replay: no events found for aggregateId={}", aggregateId);
            return PaymentAggregate.empty(aggregateId);
        }
        log.info("Replaying {} events for aggregateId={}", events.size(), aggregateId);
        return PaymentAggregate.replay(aggregateId, events);
    }

    /**
     * 从给定事件序列重放聚合根（绕过 EventStore 直接读取）。
     *
     * <p>适用于：
     * <ul>
     *   <li>Kafka 消费端：consumer.seek + poll 加载事件后调用本方法</li>
     *   <li>测试场景：直接构造事件序列验证重放逻辑</li>
     *   <li>跨服务重放：从外部系统导入事件序列</li>
     * </ul>
     *
     * @param aggregateId 聚合根 ID
     * @param events      事件序列（按版本号升序）
     * @return 重放后的聚合根
     */
    public PaymentAggregate replayFromEvents(String aggregateId, List<PaymentEvent> events) {
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId must not be blank");
        }
        if (events == null || events.isEmpty()) {
            return PaymentAggregate.empty(aggregateId);
        }
        log.info("Replaying {} events from external source for aggregateId={}", events.size(), aggregateId);
        return PaymentAggregate.replay(aggregateId, events);
    }

    /**
     * 增量重放：聚合根已重放至某版本，仅应用后续事件。
     *
     * @param aggregate      已重放的聚合根
     * @param fromVersion    起始版本号（含）
     * @return 应用后续事件后的聚合根
     */
    public PaymentAggregate replayIncremental(PaymentAggregate aggregate, long fromVersion) {
        if (aggregate == null) {
            throw new IllegalArgumentException("aggregate must not be null");
        }
        String aggregateId = aggregate.getAggregateId();
        List<PaymentEvent> events = eventStore.loadEventsFromVersion(aggregateId, fromVersion);
        if (events == null || events.isEmpty()) {
            log.debug("Incremental replay: no new events for aggregateId={}, fromVersion={}", aggregateId, fromVersion);
            return aggregate;
        }
        log.info("Incremental replaying {} events for aggregateId={}, fromVersion={}",
                events.size(), aggregateId, fromVersion);
        for (PaymentEvent event : events) {
            aggregate.doApply(event);
        }
        return aggregate;
    }

    /**
     * 全量重放：加载 EventStore 中全部事件，按聚合根分组重建。
     *
     * <p>适用于读模型重建（CQRS 投影侧从零构建）。
     * 注意：本方法仅适用于支持 {@link EventStore#loadAllEvents} 的实现（如 InMemoryEventStore）。
     *
     * @return 聚合根 ID → 重放后的聚合根
     */
    public java.util.Map<String, PaymentAggregate> replayAll() {
        List<PaymentEvent> allEvents = eventStore.loadAllEvents();
        java.util.Map<String, PaymentAggregate> aggregates = new java.util.LinkedHashMap<>();
        if (allEvents == null || allEvents.isEmpty()) {
            log.info("Replay all: no events in store");
            return aggregates;
        }
        log.info("Replay all: {} events to replay", allEvents.size());
        for (PaymentEvent event : allEvents) {
            String aggId = event.getAggregateId();
            PaymentAggregate agg = aggregates.computeIfAbsent(aggId, PaymentAggregate::empty);
            agg.doApply(event);
        }
        log.info("Replay all: reconstructed {} aggregates", aggregates.size());
        return aggregates;
    }

    /**
     * 校验事件序列版本号连续性（重放前置校验）。
     *
     * @param events 事件序列
     * @return {@code true} 若版本号从 1 开始连续递增
     */
    public boolean validateVersionContinuity(List<PaymentEvent> events) {
        if (events == null || events.isEmpty()) {
            return true;
        }
        long expected = 1L;
        for (PaymentEvent event : events) {
            if (event.getVersion() != expected) {
                log.warn("Version discontinuity detected: expected={}, actual={}, aggregateId={}, eventId={}",
                        expected, event.getVersion(), event.getAggregateId(), event.getEventId());
                return false;
            }
            expected++;
        }
        return true;
    }
}