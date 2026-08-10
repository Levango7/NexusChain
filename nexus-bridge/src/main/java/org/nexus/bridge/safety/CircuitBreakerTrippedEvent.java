package org.nexus.bridge.safety;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * 跨链桥熔断器触发事件。
 *
 * <p>由 {@link DefaultCircuitBreaker#trip(String)} 在熔断状态切换为「已触发」时发布。
 * 监听方（如告警/对账/通知模块）可通过 {@code @EventListener} 订阅本事件，
 * 实现外部告警推送、Slack/钉钉通知、自动对账触发等扩展逻辑，无需耦合熔断器实现本身。</p>
 *
 * <p>事件语义：
 * <ul>
 *   <li>仅当熔断状态从「未触发」切换为「已触发」时发布（重复 trip 不重复发布）</li>
 *   <li>事件不可变、线程安全，可在异步监听器中跨线程传递</li>
 *   <li>{@code source} 为发布事件的 {@link CircuitBreaker} 实例</li>
 * </ul></p>
 *
 * <p>注意：当前 {@link CircuitBreaker} 接口尚未被 bridge 主流程注入调用，
 * 本事件类为骨架预备——未来接入跨链操作前置检查时，监听方即可零改动复用。</p>
 *
 * @since 1.9.2
 * @see CircuitBreaker
 * @see DefaultCircuitBreaker
 */
public class CircuitBreakerTrippedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    /** 熔断原因（人类可读，用于告警展示） */
    private final String reason;
    /** 事件发生时间戳 */
    private final Instant occurredAt;

    /**
     * 构造熔断触发事件。
     *
     * @param source    发布事件的熔断器实例
     * @param reason    熔断原因；允许 {@code null} 但建议始终提供可读原因
     * @param occurredAt 事件发生时间戳
     */
    public CircuitBreakerTrippedEvent(Object source, String reason, Instant occurredAt) {
        super(source);
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    /**
     * 获取熔断原因。
     *
     * @return 熔断原因；未提供时返回 {@code null}
     */
    public String getReason() {
        return reason;
    }

    /**
     * 获取事件发生时间戳。
     *
     * @return 事件发生时间戳
     */
    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public String toString() {
        return "CircuitBreakerTrippedEvent{reason='" + reason + "', occurredAt=" + occurredAt + "}";
    }
}