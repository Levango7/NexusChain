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
 * <p><b>状态变更咽喉钩子（2026-10-03 A3；2026-10-05 修订口径）</b>：订单状态变更
 * 应统一经由 {@link #transition}——风控限额累加器（{@code RiskLimitAccrualService}）
 * 在此挂单一钩子，追踪「PAID+PAYING 成员集」的进出，避免在调用点散落记账逻辑。
 * 钩子异常只告警不阻断（状态机本体可用性优先）。</p>
 *
 * <p><b>收敛现状（2026-10-05 实测；原文"全部收敛"不实，已修正）</b>：
 * 受守卫的 {@link #transition} 调用 <b>19 处</b>；直接 {@code setStatus} 绕过守卫
 * <b>10 处</b>，其中 <b>3 处在生产路径</b>——{@code CompensationService}（×2）与
 * {@code ReconciliationTask}（×1），已于 2026-10-05 全部改为 {@code transition}。
 * 其余 7 处为沙箱模拟（{@code SandboxSimulationService} ×6，非资金路径）与
 * 订单新建时的初始赋值（{@code OrderServiceImpl}，无前态、不构成状态转换）。</p>
 *
 * <p><b>⚠️ 误差方向提醒</b>：本类曾称"累加误差方向为偏紧=fail-safe"——该结论
 * <b>仅适用于钩子抛异常</b>的情形。若因绕过 {@link #transition} 而<b>钩子根本未被
 * 触发</b>，"进入成员集"的转换（如 REFUND_PENDING→PAID）会漏记 +amount，方向为
 * <b>偏松（fail-unsafe）</b>，即可能放行超额交易。两种情形的误差方向相反，不可混用。</p>
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