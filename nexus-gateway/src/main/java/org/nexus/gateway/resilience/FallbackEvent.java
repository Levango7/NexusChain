package org.nexus.gateway.resilience;

import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * 降级事件 — 当主 Connector 熔断后路由到备选 Connector 时发布。
 *
 * <p>通过 Spring {@link ApplicationEvent} 机制发布，订阅者可据此触发告警、
 * 记录审计日志或更新路由统计。</p>
 */
public class FallbackEvent extends ApplicationEvent {

    private final String fromConnector;
    private final String toConnector;
    private final String reason;
    private final Instant fallbackTimestamp;

    /**
     * 构造降级事件。
     *
     * @param source        事件发布者
     * @param fromConnector 发生熔断的主 Connector 类型
     * @param toConnector   降级路由到的备选 Connector 类型
     * @param reason        降级原因（如 "CIRCUIT_OPEN", "CIRCUIT_HALF_OPEN"）
     */
    public FallbackEvent(Object source, String fromConnector, String toConnector, String reason) {
        super(source);
        this.fromConnector = fromConnector;
        this.toConnector = toConnector;
        this.reason = reason;
        this.fallbackTimestamp = Instant.now();
    }

    public String getFromConnector() {
        return fromConnector;
    }

    public String getToConnector() {
        return toConnector;
    }

    public String getReason() {
        return reason;
    }

    /**
     * 降级事件发生的时间戳。
     * 注意：不覆盖 ApplicationEvent.getTimestamp()（该方法为 final），
     * 使用独立字段记录降级时间。
     */
    public Instant getFallbackTimestamp() {
        return fallbackTimestamp;
    }
}