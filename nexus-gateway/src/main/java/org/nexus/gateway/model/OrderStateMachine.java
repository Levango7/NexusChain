package org.nexus.gateway.model;

import java.util.*;

/**
 * Explicit order state machine with guarded transitions.
 *
 * Valid transitions:
 *   PENDING  -> PAYING, EXPIRED, FAILED (risk/compliance rejection)
 *   PAYING   -> PAID, FAILED, EXPIRED
 *   PAID     -> REFUNDED
 *   EXPIRED  -> (terminal)
 *   REFUNDED -> (terminal)
 *   FAILED   -> PENDING (retry)
 */
public final class OrderStateMachine {

    private static final Map<PaymentOrder.OrderStatus, Set<PaymentOrder.OrderStatus>> TRANSITIONS;

    static {
        Map<PaymentOrder.OrderStatus, Set<PaymentOrder.OrderStatus>> map = new EnumMap<>(PaymentOrder.OrderStatus.class);
        map.put(PaymentOrder.OrderStatus.PENDING, EnumSet.of(
                PaymentOrder.OrderStatus.PAYING,
                PaymentOrder.OrderStatus.EXPIRED,
                PaymentOrder.OrderStatus.FAILED
        ));
        map.put(PaymentOrder.OrderStatus.PAYING, EnumSet.of(
                PaymentOrder.OrderStatus.PAID,
                PaymentOrder.OrderStatus.FAILED,
                PaymentOrder.OrderStatus.EXPIRED
        ));
        map.put(PaymentOrder.OrderStatus.PAID, EnumSet.of(
                PaymentOrder.OrderStatus.REFUNDED
        ));
        map.put(PaymentOrder.OrderStatus.FAILED, EnumSet.of(
                PaymentOrder.OrderStatus.PENDING
        ));
        map.put(PaymentOrder.OrderStatus.EXPIRED, EnumSet.noneOf(PaymentOrder.OrderStatus.class));
        map.put(PaymentOrder.OrderStatus.REFUNDED, EnumSet.noneOf(PaymentOrder.OrderStatus.class));
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
    }

    /**
     * Get all valid target states from the current status.
     */
    public static Set<PaymentOrder.OrderStatus> validTargets(PaymentOrder.OrderStatus from) {
        Set<PaymentOrder.OrderStatus> targets = TRANSITIONS.get(from);
        return targets != null ? Collections.unmodifiableSet(targets) : Collections.emptySet();
    }
}