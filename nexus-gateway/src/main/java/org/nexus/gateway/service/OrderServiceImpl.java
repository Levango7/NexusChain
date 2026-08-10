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

    @Override
    @Transactional
    public PaymentOrder createOrder(CreateOrderRequest request) {
        // Idempotency check: if the same key was used before, return the existing order
        String idempotencyKey = request.getIdempotencyKey();
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            String existingId = idempotencyStore.get(idempotencyKey);
            if (existingId != null) {
                java.util.Optional<PaymentOrder> existing = orderRepository.findById(Long.parseLong(existingId));
                if (existing.isPresent()) {
                    log.info("Idempotent hit: key={}, orderId={}", idempotencyKey, existingId);
                    return existing.get();
                }
            }
        }

        PaymentOrder order = new PaymentOrder();
        order.setOrderNo(generateOrderNo());
        order.setMerchantId(Long.parseLong(request.getMerchantId()));
        // P4-T6 多租户改造：从 TenantContext 填充 tenantId 实现数据隔离
        order.setTenantId(org.nexus.gateway.tenant.TenantContext.getCurrentTenantId());
        order.setAmount(request.getAmount());
        order.setTokenSymbol(request.getTokenSymbol() != null ? request.getTokenSymbol() : "NEX");
        order.setDescription(request.getDescription());
        order.setPayerAddress(request.getPayerAddress());
        order.setPayeeAddress(resolveSettlementAddress(request.getMerchantId()));
        order.setCheckoutToken(UUID.randomUUID().toString().replace("-", ""));
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