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

    /**
     * v2 批量建单（事务下沉修复，2026-09-11 质量审查 B5）。
     *
     * <p>原 v2 Controller 的 batchCreate 在 Controller 方法上标
     * {@code @Transactional}——事务边界应在 Service 层（代理语义与
     * Controller 解耦）。本方法承接批量建单循环：
     * <ul>
     *   <li>{@code ALL_OR_NOTHING}：任一项失败抛
     *       {@link BatchCreateException}（携带失败下标与原因），整体回滚</li>
     *   <li>{@code PARTIAL}：失败项记入 {@code failures} 继续执行</li>
     * </ul>
     * 结果结构由调用方映射为协议响应（Controller 不再持有事务）。</p>
     *
     * @param requests 逐项建单请求（顺序即响应下标）
     * @param allOrNothing true = 任一失败全回滚；false = 允许部分成功
     * @return 成功/失败配对结果
     * @throws BatchCreateException allOrNothing 模式下首个失败时抛出（事务回滚）
     */
    BatchCreateResult batchCreate(List<CreateOrderRequest> requests, boolean allOrNothing);

    /**
     * 批量建单结果（v2 batch 端点协议映射的中间结构）。
     *
     * @param orders   成功项：下标与实体一一对应（仅成功项）
     * @param failures 失败项：下标 + 错误消息（PARTIAL 模式下非空）
     */
    record BatchCreateResult(List<OrderCreated> orders, List<OrderFailed> failures) {
        /** 单项成功：originalIndex 为请求列表中的下标。 */
        public record OrderCreated(int originalIndex, PaymentOrder order) { }
        /** 单项失败：originalIndex + 错误消息（无异常对象，协议安全）。 */
        public record OrderFailed(int originalIndex, String errorMessage) { }
    }

    /**
     * ALL_OR_NOTHING 模式批量建单的失败信号——触发事务回滚，
     * 由 v2 异常处理器转换为 422 响应。
     */
    class BatchCreateException extends RuntimeException {
        private final int failedIndex;

        public BatchCreateException(int failedIndex, Throwable cause) {
            super("Batch failed at index " + failedIndex + ": " + cause.getMessage(), cause);
            this.failedIndex = failedIndex;
        }

        public int getFailedIndex() { return failedIndex; }
    }
}
