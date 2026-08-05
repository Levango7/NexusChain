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
 */
@Service
public class DefaultSettlementService implements SettlementService {

    private static final Logger log = LoggerFactory.getLogger(DefaultSettlementService.class);

    /**
     * Default settlement fee in basis points (0.5%).
     * TODO: replace with a per-merchant FeeSchedule once merchant fee config exists.
     */
    private static final int DEFAULT_FEE_BASIS_POINTS = 50;

    private final SettlementBatchRepository batchRepository;
    private final PaymentOrderRepository orderRepository;
    private final ClearingEngine clearingEngine;

    public DefaultSettlementService(SettlementBatchRepository batchRepository,
                                    PaymentOrderRepository orderRepository,
                                    ClearingEngine clearingEngine) {
        this.batchRepository = batchRepository;
        this.orderRepository = orderRepository;
        this.clearingEngine = clearingEngine;
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
        BigDecimal fee = gross.multiply(BigDecimal.valueOf(DEFAULT_FEE_BASIS_POINTS))
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
                batch.setStatus(SettlementBatch.BatchStatus.COMPLETED);
                batch.setExecutedAt(LocalDateTime.now());
                // TODO: on-chain settlement transfer via exchange-wallet; set real chainTxHash.
                // 链上结算转账尚未接入，暂以本地批次号作为执行凭证占位。
                batch.setChainTxHash("SETTLE-" + batch.getBatchNo());
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
