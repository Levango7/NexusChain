package org.nexus.gateway.clearing;

import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.settlement.clearing.ClearingEngine;
import org.nexus.settlement.clearing.ClearingOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Settlement service implementation.
 *
 * <p>The gateway owns batch lifecycle (window query, fee computation, batch
 * persistence) and delegates the actual net settlement to the
 * {@code nexus-settlement} {@link ClearingEngine}.</p>
 *
 * <p><b>链上结算未接入：</b>当前 {@link ClearingEngine} 仅完成记账（批次聚合、
 * 净额计算与状态流转），真实的链上转账（exchange-wallet 签名与广播）尚未接入。
 * 因此在结算执行完成后 {@link SettlementBatch#getChainTxHash()} 保持 {@code null}，
 * 不得以任何占位串冒充链上交易哈希。接入链上转账后，再用真实交易哈希回填。</p>
 */
@Service
public class DefaultSettlementService implements SettlementService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSettlementService.class);

    /**
     * Default settlement fee in basis points (0.5%).
     * #19: per-merchant FeeSchedule 已落地——商户 {@code feeBasisPoints} 配置优先，
     * 未配置时回退此默认值。
     */
    private static final int DEFAULT_FEE_BASIS_POINTS = 50;

    private final SettlementBatchRepository batchRepository;
    private final org.nexus.gateway.service.MerchantServiceImpl merchantService;
    private final PaymentOrderRepository orderRepository;
    private final ClearingEngine clearingEngine;

    public DefaultSettlementService(SettlementBatchRepository batchRepository,
                                    PaymentOrderRepository orderRepository,
                                    ClearingEngine clearingEngine,
                                    org.nexus.gateway.service.MerchantServiceImpl merchantService) {
        this.batchRepository = batchRepository;
        this.orderRepository = orderRepository;
        this.clearingEngine = clearingEngine;
        this.merchantService = merchantService;
    }

    /**
     * 解析商户结算费率（#19 per-merchant FeeSchedule）：
     * 商户 {@code feeBasisPoints} 配置优先（>0），未配置/无效回退默认 50bp。
     */
    private int resolveMerchantFeeBasisPoints(Long merchantId) {
        try {
            if (merchantId != null && merchantService != null) {
                var merchantOpt = merchantService.findById(merchantId);
                if (merchantOpt.isPresent()) {
                    Integer fee = merchantOpt.get().getFeeBasisPoints();
                    if (fee != null && fee > 0) {
                        return fee;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("resolveMerchantFeeBasisPoints failed for merchant={}, fallback default: {}",
                    merchantId, e.getMessage());
        }
        return DEFAULT_FEE_BASIS_POINTS;
    }

    @Override
    public SettlementBatch createSettlementBatch(Long merchantId, SettlementPeriod period) {
        if (merchantId == null || period == null) {
            throw new IllegalArgumentException("merchantId and period are required");
        }

        LocalDateTime[] window = periodWindow(period);
        List<PaymentOrder> orders = orderRepository.findByMerchantIdAndStatusAndPaidAtBetween(
                merchantId, PaymentOrder.OrderStatus.PAID, window[0], window[1]);

        SettlementBatch batch = new SettlementBatch();
        batch.setBatchNo("SB" + System.currentTimeMillis()
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        batch.setMerchantId(merchantId);
        batch.setPeriod(period);
        batch.setWindowStart(window[0]);
        batch.setWindowEnd(window[1]);

        BigDecimal gross = orders.stream()
                .map(PaymentOrder::getAmount)
                .filter(a -> a != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Fee = gross * feeBps / 10000, rounded half-up; net = gross - fee
        // #19: 商户费率优先（per-merchant FeeSchedule），未配置回退默认 50bp
        int feeBps = resolveMerchantFeeBasisPoints(merchantId);
        BigDecimal fee = gross.multiply(BigDecimal.valueOf(feeBps))
                .divide(BigDecimal.valueOf(10000), 0, RoundingMode.HALF_UP);

        batch.setTotalAmount(gross);
        batch.setFeeAmount(fee);
        batch.setNetAmount(gross.subtract(fee));
        batch.setStatus(SettlementBatch.BatchStatus.OPEN);
        batch.setTransactionIdsCsv(orders.stream()
                .map(o -> String.valueOf(o.getId()))
                .collect(Collectors.joining(",")));

        SettlementBatch saved = batchRepository.save(batch);
        log.info("Settlement batch created: batchNo={}, merchantId={}, orders={}, gross={}, fee={}, net={}",
                saved.getBatchNo(), merchantId, orders.size(), gross, fee, saved.getNetAmount());
        return saved;
    }

    /**
     * Execute the settlement of a batch.
     *
     * <p><b>链上结算未接入，settlement 仅记账：</b>本方法驱动批次状态流转并委托
     * {@link ClearingEngine} 完成净额结算的记账逻辑；真实的链上转账尚未接入，因此
     * 批次被标记为 COMPLETED 仅代表记账完成，{@link SettlementBatch#getChainTxHash()}
     * 保持 {@code null}，不做任何占位伪造。待接入 exchange-wallet 链上转账后，
     * 应用真实交易哈希回填 chainTxHash 并补充异步对账。</p>
     */
    @Override
    public SettlementBatch executeSettlement(Long batchId) {
        SettlementBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement batch not found: " + batchId));

        if (batch.getStatus() != SettlementBatch.BatchStatus.OPEN) {
            throw new IllegalStateException("Batch is not in OPEN status: " + batch.getStatus());
        }

        batch.setStatus(SettlementBatch.BatchStatus.EXECUTING);
        batchRepository.save(batch);

        try {
            // Delegate net settlement to nexus-settlement ClearingEngine
            org.nexus.settlement.clearing.SettlementBatch engineBatch = toEngineBatch(batch);
            org.nexus.settlement.clearing.SettlementBatch cleared = clearingEngine.batchClear(engineBatch);

            boolean settled = cleared != null
                    && cleared.getStatus() == org.nexus.settlement.clearing.SettlementBatch.BatchStatus.SETTLED;

            if (settled) {
                // 链上结算转账尚未接入：ClearingEngine 仅完成记账。
                // chainTxHash 保持 null，不得用占位串冒充链上哈希；
                // 接入 exchange-wallet 真实转账并取得链上交易哈希后再回填。
                batch.setStatus(SettlementBatch.BatchStatus.COMPLETED);
                batch.setExecutedAt(LocalDateTime.now());
                log.info("Settlement batch {} marked COMPLETED (bookkeeping only): "
                        + "on-chain transfer NOT executed, chainTxHash stays null", batch.getBatchNo());
            } else {
                batch.setStatus(SettlementBatch.BatchStatus.FAILED);
            }
        } catch (Exception e) {
            log.error("Settlement execution failed for batch {}: {}", batch.getBatchNo(), e.getMessage());
            batch.setStatus(SettlementBatch.BatchStatus.FAILED);
        }

        SettlementBatch saved = batchRepository.save(batch);
        log.info("Settlement executed: batchNo={}, status={}", saved.getBatchNo(), saved.getStatus());
        return saved;
    }

    @Override
    public SettlementBatch getSettlementStatus(Long batchId) {
        if (batchId == null) {
            return null;
        }
        return batchRepository.findById(batchId).orElse(null);
    }

    @Override
    public List<SettlementBatch> generateSettlementReport(Long merchantId, SettlementPeriod period) {
        if (merchantId == null) {
            return new ArrayList<>();
        }
        if (period != null) {
            return batchRepository.findByMerchantIdAndPeriod(merchantId, period);
        }
        return batchRepository.findByMerchantId(merchantId);
    }

    /**
     * Compute the settlement window [start, end) for a period.
     */
    private LocalDateTime[] periodWindow(SettlementPeriod period) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime end = now;
        LocalDateTime start = switch (period) {
            case T0 -> now.toLocalDate().atStartOfDay();
            case T1 -> now.toLocalDate().minusDays(1).atStartOfDay();
            case WEEKLY -> now.minusDays(7);
            case MONTHLY -> now.minusDays(30);
        };
        return new LocalDateTime[]{start, end};
    }

    /**
     * Map the gateway batch entity to the nexus-settlement engine batch DTO.
     */
    private org.nexus.settlement.clearing.SettlementBatch toEngineBatch(SettlementBatch batch) {
        org.nexus.settlement.clearing.SettlementBatch engineBatch =
                new org.nexus.settlement.clearing.SettlementBatch();
        engineBatch.setBatchNo(batch.getBatchNo());
        engineBatch.setSettlementAmount(batch.getNetAmount());
        engineBatch.setStatus(org.nexus.settlement.clearing.SettlementBatch.BatchStatus.PENDING);

        List<ClearingOrder> orders = new ArrayList<>();
        for (Long txId : batch.getTransactionList()) {
            orderRepository.findById(txId).ifPresent(order -> {
                ClearingOrder co = new ClearingOrder();
                co.setOrderId(String.valueOf(order.getId()));
                co.setMerchantId(String.valueOf(order.getMerchantId()));
                co.setAmount(order.getAmount());
                co.setCurrency(order.getTokenSymbol());
                co.setSettlementCycle(batch.getPeriod().name());
                co.setStatus(ClearingOrder.OrderStatus.PENDING);
                orders.add(co);
            });
        }
        engineBatch.setOrders(orders);
        return engineBatch;
    }
}
