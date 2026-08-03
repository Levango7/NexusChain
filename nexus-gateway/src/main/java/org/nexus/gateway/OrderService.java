package org.nexus.gateway;

import org.nexus.gateway.dto.CreateOrderRequest;
import org.nexus.gateway.model.PaymentOrder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Order service interface covering creation, querying, and expiry cleanup.
 *
 * <p>Orders are the core unit of the payment lifecycle. Each order carries a
 * checkout token that enables the cashier (redirect) flow.</p>
 */
public interface OrderService {

    /**
     * Create a new payment order from the request DTO.
     *
     * <p>The created order is in {@link PaymentOrder.OrderStatus#PENDING} status
     * and has a unique checkout token for the cashier redirect.</p>
     *
     * @param request creation request
     * @return the persisted order entity
     */
    PaymentOrder createOrder(CreateOrderRequest request);

    /**
     * Look up an order by its database ID.
     *
     * @param orderId order ID
     * @return the order if found
     */
    Optional<PaymentOrder> findById(Long orderId);

    /**
     * Look up an order by its order number.
     *
     * @param orderNo unique order number
     * @return the order if found
     */
    Optional<PaymentOrder> findByOrderNo(String orderNo);

    /**
     * Look up an order by its checkout token (used by the cashier flow).
     *
     * @param checkoutToken checkout token
     * @return the order if found
     */
    Optional<PaymentOrder> findByCheckoutToken(String checkoutToken);

    /**
     * List orders for a merchant, optionally filtered by status.
     *
     * @param merchantId merchant ID
     * @param status     optional status filter; {@code null} for all
     * @return list of matching orders
     */
    List<PaymentOrder> listByMerchant(Long merchantId, PaymentOrder.OrderStatus status);

    /**
     * Sweep and mark expired orders.
     *
     * <p>Orders whose {@code expiresAt} has passed and are still in
     * {@link PaymentOrder.OrderStatus#PENDING} are transitioned to
     * {@link PaymentOrder.OrderStatus#EXPIRED}.</p>
     *
     * @param cutoff timestamp threshold; orders expiring before this are swept
     * @return the number of orders marked expired
     */
    int sweepExpired(LocalDateTime cutoff);
}
