package org.nexus.gateway.event.sourcing;

import java.util.List;

/**
 * 事件存储接口（Event Store）。
 *
 * <p>事件溯源的核心抽象：聚合根的状态变更以事件序列的形式持久化，
 * 通过重放事件序列可重建聚合根任意历史时刻的状态。
 *
 * <p>实现方需保证：
 * <ul>
 *   <li>{@link #append} 操作原子性：同一聚合根并发追加需通过版本号（乐观锁）串行化</li>
 *   <li>{@link #loadEvents} 返回按版本号升序排列的事件序列</li>
 *   <li>事件持久化不可变：已写入的事件不可修改、不可删除</li>
 * </ul>
 *
 * <p>本接口不依赖任何具体存储技术（Kafka / RDBMS / 内存），
 * 由 {@link KafkaEventStore}（生产）与 {@link InMemoryEventStore}（测试）提供实现。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
public interface EventStore {

    /**
     * 追加事件到指定聚合根的事件流。
     *
     * @param event 待追加的事件（携带聚合根 ID 与目标版本号）
     * @throws OptimisticLockException 当目标版本号与当前流尾版本不匹配（并发冲突）
     * @throws EventStoreException 当存储层发生不可恢复错误
     */
    void append(PaymentEvent event);

    /**
     * 加载指定聚合根的全部历史事件（按版本号升序）。
     *
     * @param aggregateId 聚合根 ID
     * @return 事件序列（空列表表示该聚合根无事件）
     */
    List<PaymentEvent> loadEvents(String aggregateId);

    /**
     * 加载指定聚合根从 {@code fromVersion} 起的全部事件（含 fromVersion，升序）。
     *
     * <p>用于增量重放：聚合根已重放至 {@code fromVersion - 1}，
     * 仅需追加应用后续事件即可达到最新状态。
     *
     * @param aggregateId 聚合根 ID
     * @param fromVersion 起始版本号（含）
     * @return 事件序列
     */
    List<PaymentEvent> loadEventsFromVersion(String aggregateId, long fromVersion);

    /**
     * 查询指定聚合根当前事件流的最大版本号。
     *
     * @param aggregateId 聚合根 ID
     * @return 当前版本号（无事件返回 0）
     */
    long currentVersion(String aggregateId);

    /**
     * 加载全部聚合根的全部事件（按聚合根 ID、版本号升序）。
     *
     * <p>用于全量重放（如读模型重建）。生产环境应分页/分批加载，
     * 本接口默认实现可返回流或迭代器，由具体实现决定。
     *
     * @return 全部事件序列
     */
    List<PaymentEvent> loadAllEvents();

    /**
     * 乐观锁冲突异常。
     */
    class OptimisticLockException extends RuntimeException {
        public OptimisticLockException(String message) {
            super(message);
        }
    }

    /**
     * 事件存储层异常。
     */
    class EventStoreException extends RuntimeException {
        public EventStoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}