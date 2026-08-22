package org.nexus.bridge.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 熔断器默认骨架实现。
 *
 * <p>基于 AtomicReference 实现线程安全的熔断状态切换。
 * 当前仅维护状态与原因，未接入自动恢复策略（如半开探测、定时重置）。</p>
 *
 * <p>本类为 P0-5 修复后的最小可用实现：
 * <ul>
 *   <li>{@link #trip(String)}：切换状态 + WARN 日志告警 + 发布 {@link CircuitBreakerTrippedEvent}</li>
 *   <li>{@link #reset()}：切换状态 + INFO 日志记录（含调用栈标识，便于审计谁重置了熔断）</li>
 *   <li>{@link #isTripped()}：纯查询，调用方应在跨链操作前主动检查</li>
 * </ul></p>
 *
 * <p><b>权限校验说明</b>：{@link #reset()} 不在本类内部校验调用权限（多签 / 治理提案），
 * 应由调用方（如治理 API / 运维 CLI）在调用前完成权限校验。本类仅负责状态切换与审计日志，
 * 保持单一职责。调用方示例：
 * <pre>{@code
 * if (!governanceService.isAuthorized(caller, "circuit_breaker.reset")) {
 *     throw new PermissionDeniedException(...);
 * }
 * circuitBreaker.reset();  // 通过校验后才调用
 * }</pre></p>
 *
 * <p><b>接入说明</b>：当前 {@link CircuitBreaker} 接口尚未被 bridge 主流程注入调用，
 * 本实现为骨架预备。未来在 {@code BridgeServiceImpl} 的 lock/mint/burn/unlock 入口
 * 接入 {@code isTripped()} 前置检查后，即可零改动复用本类的事件发布与日志能力。</p>
 *
 * @since 1.2
 */
@Component
public class DefaultCircuitBreaker implements CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(DefaultCircuitBreaker.class);

    private final AtomicReference<String> tripReason = new AtomicReference<>(null);

    /**
     * Spring 事件发布器，用于发布 {@link CircuitBreakerTrippedEvent}。
     *
     * <p>通过 {@code required = false} 注入，单元测试或非 Spring 容器场景下为 {@code null}，
     * 此时 {@link #trip(String)} 仅记录日志、跳过事件发布，不影响熔断核心语义。</p>
     */
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    @Override
    public void trip(String reason) {
        String previous = tripReason.getAndSet(reason);
        // 状态从「未触发」切换为「已触发」时告警 + 发布事件；重复 trip 仅更新原因、降级为 DEBUG
        if (previous == null) {
            log.warn("跨链桥熔断器触发: reason={}", reason);
            publishTrippedEvent(reason);
        } else {
            log.debug("跨链桥熔断器原因更新: previous={}, current={}", previous, reason);
        }
    }

    @Override
    public void reset() {
        String previous = tripReason.getAndSet(null);
        if (previous != null) {
            // INFO 级别记录重置动作，便于审计谁在何时清除了熔断
            // 权限校验应在调用方完成（多签 / 治理提案），本类不承担权限校验职责
            log.info("跨链桥熔断器重置: previousReason={}", previous);
        } else {
            log.debug("跨链桥熔断器重置调用，但当前未处于熔断状态（空操作）");
        }
    }

    /**
     * 查询当前是否处于熔断状态。
     *
     * <p><b>调用方契约</b>：跨链操作（lock / mint / burn / unlock）入口应在执行前
     * 主动调用本方法，若返回 {@code true} 则拒绝操作并返回失败响应，避免在熔断期间
     * 继续提交跨链请求导致资损。示例：
     * <pre>{@code
     * if (circuitBreaker.isTripped()) {
     *     throw new BridgeCircuitOpenException(circuitBreaker.getTripReason());
     * }
     * }</pre></p>
     *
     * @return 熔断中返回 {@code true}
     */
    @Override
    public boolean isTripped() {
        return tripReason.get() != null;
    }

    @Override
    public String getTripReason() {
        return tripReason.get();
    }

    /**
     * 发布熔断触发事件。
     *
     * <p>当 {@link #eventPublisher} 可用时发布 {@link CircuitBreakerTrippedEvent}，
     * 供告警/对账/通知模块订阅；不可用时静默跳过，不影响熔断核心语义。</p>
     *
     * @param reason 熔断原因
     */
    private void publishTrippedEvent(String reason) {
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(new CircuitBreakerTrippedEvent(this, reason, Instant.now()));
            } catch (RuntimeException ex) {
                // 事件发布失败不应影响熔断状态切换本身，仅记录告警
                log.warn("熔断事件发布失败（不影响熔断语义）: reason={}, error={}", reason, ex.getMessage());
            }
        }
    }
}
