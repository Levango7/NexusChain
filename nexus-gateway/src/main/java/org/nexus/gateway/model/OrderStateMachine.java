package org.nexus.gateway.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Explicit order state machine with guarded transitions.
 *
 * Valid transitions:
 *   PENDING        -> PAYING, EXPIRED, FAILED (risk/compliance rejection)
 *   PAYING         -> SUBMITTED, PAID, FAILED, EXPIRED
 *   SUBMITTED      -> PAID (chain confirmation), FAILED (rejected), EXPIRED (timeout)
 *   PAID           -> REORGED (block reorg), REFUND_PENDING, REFUNDED, VOIDED (same-day void), REVERSED (next-day reversal)
 *   REORGED        -> PAID (re-confirmed), FAILED (unrecoverable)
 *   REFUND_PENDING -> REFUNDED (chain transfer succeeded)
 *   REFUND_PENDING -> PAID (chain transfer failed, allow retry)
 *   REFUND_PENDING -> VOIDED (same-day void of order with pending refund)
 *   EXPIRED        -> (terminal)
 *   REFUNDED       -> (terminal)
 *   FAILED         -> PENDING (retry)
 *   VOIDED         -> (terminal)
 *   REVERSED       -> (terminal)
 *
 * <p><b>状态变更咽喉钩子（2026-10-03 A3）</b>：全部订单状态变更收敛于
 * {@link #transition}——风控限额累加器（{@code RiskLimitAccrualService}）
 * 在此挂单一钩子即可精确追踪「PAID+PAYING 成员集」的进出，无需触碰
 * 20 处调用点或散落的 save。钩子异常只告警不阻断（状态机本体可用性优先；
 * 累加误差方向为偏紧=fail-safe，见 {@code RiskLimitAccrualService} 头注）。</p>
 */
public final class OrderStateMachine {

    private static final Logger log = LoggerFactory.getLogger(OrderStateMachine.class);

    /** 状态变更观察钩子（风控累加；Spring 启动注入，测试可替换/清除）。 */
    public interface TransitionHook {
        void onTransition(PaymentOrder order, PaymentOrder.OrderStatus from, PaymentOrder.OrderStatus to);
    }

    private static volatile TransitionHook hook;

    /** 注入/清除钩子（Spring 生命周期调用；测试直接调用以隔离）。 */
    public static void setHook(TransitionHook transitionHook) {
        hook = transitionHook;
    }

    private static final Map<PaymentOrder.OrderStatus, Set<PaymentOrder.OrderStatus>> TRANSITIONS;

    static {
        Map<PaymentOrder.OrderStatus, Set<PaymentOrder.OrderStatus>> map = new EnumMap<>(PaymentOrder.OrderStatus.class);
        map.put(PaymentOrder.OrderStatus.PENDING, EnumSet.of(
                PaymentOrder.OrderStatus.PAYING,
                PaymentOrder.OrderStatus.EXPIRED,
                PaymentOrder.OrderStatus.FAILED
        ));
        map.put(PaymentOrder.OrderStatus.PAYING, EnumSet.of(
                PaymentOrder.OrderStatus.SUBMITTED,
                PaymentOrder.OrderStatus.PAID,
                PaymentOrder.OrderStatus.FAILED,
                PaymentOrder.OrderStatus.EXPIRED
        ));
        map.put(PaymentOrder.OrderStatus.SUBMITTED, EnumSet.of(
                PaymentOrder.OrderStatus.PAID,
                PaymentOrder.OrderStatus.FAILED,
                PaymentOrder.OrderStatus.EXPIRED
        ));
        map.put(PaymentOrder.OrderStatus.PAID, EnumSet.of(
                PaymentOrder.OrderStatus.REORGED,
                PaymentOrder.OrderStatus.REFUND_PENDING,
                PaymentOrder.OrderStatus.REFUNDED,
                PaymentOrder.OrderStatus.VOIDED,
                PaymentOrder.OrderStatus.REVERSED
        ));
        map.put(PaymentOrder.OrderStatus.REORGED, EnumSet.of(
                PaymentOrder.OrderStatus.PAID,
                PaymentOrder.OrderStatus.FAILED
        ));
        map.put(PaymentOrder.OrderStatus.REFUND_PENDING, EnumSet.of(
                PaymentOrder.OrderStatus.REFUNDED,
                PaymentOrder.OrderStatus.PAID,
                PaymentOrder.OrderStatus.VOIDED
        ));
        map.put(PaymentOrder.OrderStatus.FAILED, EnumSet.of(
                PaymentOrder.OrderStatus.PENDING
        ));
        map.put(PaymentOrder.OrderStatus.EXPIRED, EnumSet.noneOf(PaymentOrder.OrderStatus.class));
        map.put(PaymentOrder.OrderStatus.REFUNDED, EnumSet.noneOf(PaymentOrder.OrderStatus.class));
        map.put(PaymentOrder.OrderStatus.VOIDED, EnumSet.noneOf(PaymentOrder.OrderStatus.class));
        map.put(PaymentOrder.OrderStatus.REVERSED, EnumSet.noneOf(PaymentOrder.OrderStatus.class));
        TRANSITIONS = Collections.unmodifiableMap(map);
    }

    private OrderStateMachine() {}

    /**
     * Check whether a transition from current status to target status is allowed.
     */
    public static boolean canTransition(PaymentOrder.OrderStatus from, PaymentOrder.OrderStatus to) {
        Set<PaymentOrder.OrderStatus> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    /**
     * Perform a guarded state transition on the order.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public static void transition(PaymentOrder order, PaymentOrder.OrderStatus target) {
        PaymentOrder.OrderStatus current = order.getStatus();
        if (!canTransition(current, target)) {
            throw new IllegalStateException(
                    String.format("Illegal order transition: %s -> %s (orderNo=%s)",
                            current, target, order.getOrderNo()));
        }
        order.setStatus(target);

        // 咽喉钩子：状态已变更、save 由调用方随后执行——钩子先于 save 记账，
        // 若 save 失败则累加偏多（限额偏紧=fail-safe 方向，服务头注有分析）。
        TransitionHook h = hook;
        if (h != null) {
            try {
                h.onTransition(order, current, target);
            } catch (RuntimeException e) {
                log.warn("Order transition hook failed (accrual may drift tight): {} -> {} (orderNo={}): {}",
                        current, target, order.getOrderNo(), e.getMessage());
            }
        }
    }

    /**
     * Get all valid target states from the current status.
     */
    public static Set<PaymentOrder.OrderStatus> validTargets(PaymentOrder.OrderStatus from) {
        Set<PaymentOrder.OrderStatus> targets = TRANSITIONS.get(from);
        return targets != null ? Collections.unmodifiableSet(targets) : Collections.emptySet();
    }
}