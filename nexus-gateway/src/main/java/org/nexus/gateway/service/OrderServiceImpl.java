package org.nexus.gateway.service;

import org.nexus.gateway.OrderService;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.dto.CreateOrderRequest;
import org.nexus.gateway.model.Merchant;
import org.nexus.gateway.model.OrderStateMachine;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.MerchantRepository;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import org.nexus.gateway.ratelimit.IdempotencyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final PaymentOrderRepository orderRepository;
    private final MerchantRepository merchantRepository;
    private final GatewayConfig gatewayConfig;

    private final IdempotencyStore idempotencyStore;

    public OrderServiceImpl(PaymentOrderRepository orderRepository,
                            MerchantRepository merchantRepository,
                            GatewayConfig gatewayConfig,
                            IdempotencyStore idempotencyStore) {
        this.orderRepository = orderRepository;
        this.merchantRepository = merchantRepository;
        this.gatewayConfig = gatewayConfig;
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * 幂等键的「创建中」占位值（P1，2026-09-17）。
     *
     * <p>与真实订单主键（纯数字）在形态上可区分，因此读取时能判定
     * 「已有订单」还是「仍在创建中」。</p>
     */
    private static final String IN_FLIGHT = "__IN_FLIGHT__";

    /**
     * 由幂等映射值解析出订单；占位值、空值、非数字值均返回 null。
     */
    private PaymentOrder loadOrderByIdempotencyValue(String value) {
        if (value == null || IN_FLIGHT.equals(value)) {
            return null;
        }
        try {
            return orderRepository.findById(Long.parseLong(value)).orElse(null);
        } catch (NumberFormatException e) {
            log.warn("Idempotency store holds a non-numeric value, ignoring: {}", value);
            return null;
        }
    }

    @Override
    @Transactional
    public PaymentOrder createOrder(CreateOrderRequest request) {
        // Idempotency check: if the same key was used before, return the existing order
        String idempotencyKey = request.getIdempotencyKey();
        boolean hasKey = idempotencyKey != null && !idempotencyKey.isEmpty();

        // P1（2026-09-17 修复）：原实现为 get → 创建 → put 的 check-then-act，
        // 并发下两个相同 idempotencyKey 的请求会同时读到 null 并各自建单，
        // 幂等完全失效（产生重复订单）。现改为「原子占位 → 创建 → 回填」。
        if (hasKey) {
            // 快路径：已有完成映射
            PaymentOrder done = loadOrderByIdempotencyValue(idempotencyStore.get(idempotencyKey));
            if (done != null) {
                log.info("Idempotent hit: key={}, orderId={}", idempotencyKey, done.getId());
                return done;
            }
            // 原子占位：只有胜者获得创建权
            if (!idempotencyStore.putIfAbsent(idempotencyKey, IN_FLIGHT)) {
                // 竞争失败。对手可能刚完成（读到真实 id），也可能仍在创建中。
                PaymentOrder other = loadOrderByIdempotencyValue(idempotencyStore.get(idempotencyKey));
                if (other != null) {
                    log.info("Idempotent hit after race: key={}, orderId={}", idempotencyKey, other.getId());
                    return other;
                }
                // 仍在创建中：拒绝并让调用方重试，绝不放行第二次创建
                throw new IllegalStateException(
                        "Duplicate order request in flight for idempotencyKey=" + idempotencyKey);
            }
        }

        try {
            PaymentOrder order = new PaymentOrder();
            order.setOrderNo(generateOrderNo());
            order.setMerchantId(Long.parseLong(request.getMerchantId()));
            // P4-T6 多租户改造：从 TenantContext 填充 tenantId 实现数据隔离
            order.setTenantId(org.nexus.gateway.tenant.TenantContext.getCurrentTenantId());
            order.setAmount(request.getAmount());
            order.setTokenSymbol(request.getTokenSymbol() != null ? request.getTokenSymbol() : "NEX");
            order.setDescription(request.getDescription());
            order.setPayerAddress(request.getPayerAddress());
            // A1 修复（2026-08-31 交付前审计）：持久化商户通知 URL。
            // 此前 CreateOrderRequest.notifyUrl 必填但从未落库——支付完成通知
            // 无从投递到商户端点（主链路商户通知死功能）。
            order.setNotifyUrl(request.getNotifyUrl());
            order.setPayeeAddress(resolveSettlementAddress(request.getMerchantId()));
            order.setCheckoutToken(UUID.randomUUID().toString().replace("-", ""));
            // 扫码支付：生成 qrCodeToken，用于二维码安全校验
            order.setQrCodeToken(UUID.randomUUID().toString().replace("-", ""));
            order.setStatus(PaymentOrder.OrderStatus.PENDING);

            int expiryMinutes = request.getExpiryMinutes() != null
                    ? request.getExpiryMinutes()
                    : gatewayConfig.getCheckout().getOrderExpiryMinutes();
            order.setExpiresAt(LocalDateTime.now().plusMinutes(expiryMinutes));

            PaymentOrder saved = orderRepository.save(order);
            // Store idempotency mapping
            if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
                idempotencyStore.put(idempotencyKey, String.valueOf(saved.getId()));
            }

            log.info("Order created: orderNo={}, merchantId={}, amount={}", saved.getOrderNo(), saved.getMerchantId(), saved.getAmount());
            return saved;
        } catch (RuntimeException e) {
            // P1（2026-09-17）：创建失败必须释放占位，否则该 idempotencyKey
            // 永久停留在 IN_FLIGHT，使用同一 key 的后续请求全部被拒且无法自愈。
            if (hasKey) {
                idempotencyStore.remove(idempotencyKey);
            }
            throw e;
        }
    }

    @Override
    public Optional<PaymentOrder> findById(Long orderId) {
        return orderRepository.findById(orderId);
    }

    @Override
    public Optional<PaymentOrder> findByOrderNo(String orderNo) {
        return orderRepository.findByOrderNo(orderNo);
    }

    @Override
    public Optional<PaymentOrder> findByCheckoutToken(String checkoutToken) {
        return orderRepository.findByCheckoutToken(checkoutToken);
    }

    @Override
    public List<PaymentOrder> listByMerchant(Long merchantId, PaymentOrder.OrderStatus status) {
        if (status != null) {
            return orderRepository.findByMerchantIdAndStatus(merchantId, status);
        }
        return orderRepository.findByMerchantId(merchantId);
    }

    @Override
    @Transactional
    public int sweepExpired(LocalDateTime cutoff) {
        List<PaymentOrder> expired = orderRepository.findByStatusAndExpiresAtBefore(
                PaymentOrder.OrderStatus.PENDING, cutoff);
        for (PaymentOrder order : expired) {
            OrderStateMachine.transition(order, PaymentOrder.OrderStatus.EXPIRED);
            orderRepository.save(order);
        }
        if (!expired.isEmpty()) {
            log.info("Swept {} expired orders", expired.size());
        }
        return expired.size();
    }

    @Override
    public List<PaymentOrder> findOrdersWithCursor(Long afterId, int pageSize, Long merchantId) {
        Sort sort = Sort.by(Sort.Direction.ASC, "id");
        PageRequest pageRequest = PageRequest.of(0, pageSize, sort);

        if (merchantId != null) {
            if (afterId != null) {
                return orderRepository.findByMerchantIdAndIdGreaterThan(
                        merchantId, afterId, pageRequest).getContent();
            }
            return orderRepository.findByMerchantId(merchantId, pageRequest).getContent();
        }
        if (afterId != null) {
            return orderRepository.findByIdGreaterThan(afterId, pageRequest).getContent();
        }
        return orderRepository.findAll(pageRequest).getContent();
    }

    private String generateOrderNo() {
        return "NEX" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    /**
     * v2 批量建单（事务下沉修复，2026-09-11 质量审查 B5）。
     *
     * <p>原 v2 Controller 方法级 @Transactional 迁移至此——批量循环 +
     * ALL_OR_NOTHING 回滚语义不变，事务边界归 Service 层（代理正常生效，
     * Controller 不再依赖事务代理细节）。异常转换（BatchCreateException →
     * 422）由 Controller 的异常处理器负责，本层只抛信号。</p>
     */
    @Override
    @Transactional
    public BatchCreateResult batchCreate(List<CreateOrderRequest> requests, boolean allOrNothing) {
        List<BatchCreateResult.OrderCreated> orders = new ArrayList<>();
        List<BatchCreateResult.OrderFailed> failures = new ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            try {
                PaymentOrder order = createOrder(requests.get(i));
                orders.add(new BatchCreateResult.OrderCreated(i, order));
            } catch (Exception e) {
                log.warn("Batch item {} failed: {}", i, e.getMessage());
                if (allOrNothing) {
                    // 抛出信号触发本事务回滚（全部已建单回滚）
                    throw new BatchCreateException(i, e);
                }
                failures.add(new BatchCreateResult.OrderFailed(i, e.getMessage()));
            }
        }
        return new BatchCreateResult(orders, failures);
    }

    /**
     * Resolve the merchant settlement address from the database.
     */
    private String resolveSettlementAddress(String merchantId) {
        try {
            Long id = Long.parseLong(merchantId);
            Optional<Merchant> merchant = merchantRepository.findById(id);
            if (merchant.isPresent()) {
                return merchant.get().getSettlementAddress();
            }
        } catch (NumberFormatException e) {
            // fall through
        }
        log.warn("Merchant not found for id={}, using placeholder address", merchantId);
        return "1MerchantSettlement" + merchantId;
    }
}