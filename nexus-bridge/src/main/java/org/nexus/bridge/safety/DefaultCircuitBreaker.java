package org.nexus.bridge.safety;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 熔断器默认骨架实现。
 *
 * <p>基于 AtomicReference 实现线程安全的熔断状态切换。
 * 当前仅维护状态与原因，未接入自动恢复策略。</p>
 *
 * @since 1.2
 */
@Component
public class DefaultCircuitBreaker implements CircuitBreaker {

    private final AtomicReference<String> tripReason = new AtomicReference<>(null);

    @Override
    public void trip(String reason) {
        // TODO: 触发熔断后应广播事件、阻断后续跨链请求
        tripReason.set(reason);
    }

    @Override
    public void reset() {
        // TODO: 重置前应校验调用权限（多签 / 治理提案）
        tripReason.set(null);
    }

    @Override
    public boolean isTripped() {
        return tripReason.get() != null;
    }

    @Override
    public String getTripReason() {
        return tripReason.get();
    }
}