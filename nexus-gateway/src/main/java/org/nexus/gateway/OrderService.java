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

    /**
     * v2 游标分页查询：按 id 升序返回 {@code pageSize} 条订单。
     *
     * <p>用于 v2 API 的游标分页列表端点。当 {@code afterId} 非空时返回 id &gt; afterId 的记录；
     * 当 {@code merchantId} 非空时仅返回该商户的订单。两者可组合使用。</p>
     *
     * @param afterId    游标（上一页最后一项的 id）；{@code null} 表示首页
     * @param pageSize   查询条数（调用方应传 pageSize + 1 以判断 hasMore）
     * @param merchantId 商户 ID 过滤；{@code null} 表示不限商户
     * @return 按 id 升序排列的订单列表
     */
    List<PaymentOrder> findOrdersWithCursor(Long afterId, int pageSize, Long merchantId);
}
