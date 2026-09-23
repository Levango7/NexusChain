package org.nexus.gateway.apiversion;

import org.springframework.context.ApplicationEvent;

/**
 * API 版本废弃事件。
 *
 * <p>当某 API 版本的策略被更新为 DEPRECATED/SUNSET/RETIRED 时，
 * 由 {@link ApiVersionDeprecationService#notifyDeprecation} 发布此事件。
 * 下游监听器可据此触发告警通知、文档更新、商户邮件等响应动作。</p>
 */
public class ApiVersionDeprecatedEvent extends ApplicationEvent {

    private final ApiVersionPolicy policy;

    /**
     * 构造版本废弃事件。
     *
     * @param source 事件源（通常为 ApiVersionDeprecationService）
     * @param policy 被废弃的版本策略
     */
    public ApiVersionDeprecatedEvent(Object source, ApiVersionPolicy policy) {
        super(source);
        this.policy = policy;
    }

    public ApiVersionPolicy getPolicy() {
        return policy;
    }
}