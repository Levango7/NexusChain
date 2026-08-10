package org.nexus.gateway.refund;

import org.nexus.gateway.model.PaymentOrder;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Refund policy interface defining whether and how much an order can be refunded.
 *
 * <p>Implementations encode business rules such as the refund window after
 * payment, partial-refund eligibility, and per-merchant refund caps.</p>
 */
public interface RefundPolicy {

    /**
     * Whether the given order is eligible for refund at all.
     *
     * @param order payment order
     * @return {@code true} if a refund may be requested
     */
    boolean canRefund(PaymentOrder order);

    /**
     * Maximum amount that may be refunded for the given order.
     *
     * @param order payment order
     * @return maximum refund amount, never negative
     */
    BigDecimal getMaxRefundAmount(PaymentOrder order);

    /**
     * Refund window remaining for the given order (time since paid during
     * which a refund may be requested).
     *
     * @param order payment order
     * @return remaining refund window as a {@link Duration}; {@code Duration.ZERO} if expired
     */
    Duration getRefundWindow(PaymentOrder order);
}