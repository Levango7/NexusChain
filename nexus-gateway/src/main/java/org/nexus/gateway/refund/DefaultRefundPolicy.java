package org.nexus.gateway.refund;

import org.nexus.gateway.model.PaymentOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Default refund policy implementation.
 *
 * <p>Business rules:</p>
 * <ul>
 *   <li>Only PAID orders that have not yet been REFUNDED are eligible</li>
 *   <li>Maximum refund amount equals the order amount (partial refunds allowed)</li>
 *   <li>Refund window: configurable number of days after payment
 *       ({@code nexus.gateway.refund.window-days}, default 7)</li>
 * </ul>
 */
@Component
public class DefaultRefundPolicy implements RefundPolicy {

    /** Refund window in days after payment. */
    @Value("${nexus.gateway.refund.window-days:7}")
    private long refundWindowDays;

    @Override
    public boolean canRefund(PaymentOrder order) {
        if (order == null) {
            return false;
        }
        // Only paid orders that are not already refunded can be refunded
        return order.getStatus() == PaymentOrder.OrderStatus.PAID;
    }

    @Override
    public BigDecimal getMaxRefundAmount(PaymentOrder order) {
        if (order == null || order.getAmount() == null) {
            return BigDecimal.ZERO;
        }
        // Full order amount is the cap; partial refunds are permitted up to this limit
        return order.getAmount().max(BigDecimal.ZERO);
    }

    @Override
    public Duration getRefundWindow(PaymentOrder order) {
        if (order == null || order.getPaidAt() == null) {
            return Duration.ZERO;
        }
        LocalDateTime windowEnd = order.getPaidAt().plusDays(refundWindowDays);
        Duration remaining = Duration.between(LocalDateTime.now(), windowEnd);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
