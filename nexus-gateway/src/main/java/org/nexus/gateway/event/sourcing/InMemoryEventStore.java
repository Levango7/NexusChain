package org.nexus.gateway.event.sourcing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存事件存储实现（测试用）。
 *
 * <p>使用 {@link ConcurrentHashMap} 维护每个聚合根的事件序列，
 * 通过 synchronized 块 + 版本号校验实现乐观锁。
 *
 * <p>激活条件：{@code nexus.event-sourcing.store=memory}，
 * 仅在单元测试 / 集成测试中启用，生产环境使用 {@link KafkaEventStore}。
 *
 * <p>本实现支持 {@link #loadEvents} / {@link #loadEventsFromVersion} /
 * {@link #currentVersion} / {@link #loadAllEvents} 全部接口方法，
 * 可用于 {@link EventReplayService} 的离线测试。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
@Component
@ConditionalOnProperty(prefix = "nexus.event-sourcing", name = "store", havingValue = "memory")
public class InMemoryEventStore implements EventStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventStore.class);

    /** 聚合根 ID → 事件序列（按版本号升序） */
    private final Map<String, List<PaymentEvent>> store = new ConcurrentHashMap<>();

    @Override
    public void append(PaymentEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        String aggregateId = event.getAggregateId();
        // 同一聚合根的事件追加需串行化（乐观锁校验 + 写入）
        synchronized (store) {
            List<PaymentEvent> stream = store.computeIfAbsent(aggregateId, k -> new ArrayList<>());
            long expectedVersion = event.getVersion();
            long currentMax = stream.isEmpty() ? 0L : stream.get(stream.size() - 1).getVersion();
            if (expectedVersion != currentMax + 1) {
                throw new OptimisticLockException(
                        "Optimistic lock conflict: aggregateId=" + aggregateId
                                + ", expectedVersion=" + expectedVersion
                                + ", currentMax=" + currentMax);
            }
            stream.add(event);
            log.debug("Event appended in-memory: aggregateId={}, version={}, eventType={}",
                    aggregateId, event.getVersion(), event.getEventType());
        }
    }

    @Override
    public List<PaymentEvent> loadEvents(String aggregateId) {
        List<PaymentEvent> stream = store.get(aggregateId);
        if (stream == null) {
            return Collections.emptyList();
        }
        // 返回不可变副本，防止外部修改内部状态
        return Collections.unmodifiableList(new ArrayList<>(stream));
    }

    @Override
    public List<PaymentEvent> loadEventsFromVersion(String aggregateId, long fromVersion) {
        List<PaymentEvent> stream = store.get(aggregateId);
        if (stream == null) {
            return Collections.emptyList();
        }
        return stream.stream()
                .filter(e -> e.getVersion() >= fromVersion)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override
    public long currentVersion(String aggregateId) {
        List<PaymentEvent> stream = store.get(aggregateId);
        if (stream == null || stream.isEmpty()) {
            return 0L;
        }
        return stream.get(stream.size() - 1).getVersion();
    }

    @Override
    public List<PaymentEvent> loadAllEvents() {
        return store.values().stream()
                .flatMap(List::stream)
                .sorted((a, b) -> {
                    int byAgg = a.getAggregateId().compareTo(b.getAggregateId());
                    return byAgg != 0 ? byAgg : Long.compare(a.getVersion(), b.getVersion());
                })
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * 清空全部事件（仅测试用）。
     */
    public void clear() {
        store.clear();
    }
}