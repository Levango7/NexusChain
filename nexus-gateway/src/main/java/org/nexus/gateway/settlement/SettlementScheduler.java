package org.nexus.gateway.settlement;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.nexus.settlement.clearing.ClearingEngine;
import org.nexus.settlement.clearing.ClearingOrder;
import org.nexus.settlement.clearing.SettlementBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 结算调度器（Gateway → Settlement 批量结算入口）。
 *
 * <p>定时将 {@link SettlementEventCollector} staging 中的 PENDING 清算订单
 * 打包为 {@link SettlementBatch}，调用 {@link ClearingEngine#batchClear} 批量结算。</p>
 *
 * <p>与 {@link org.nexus.gateway.execution.ReconciliationTask} 同模式：
 * <ul>
 *   <li>{@code @Scheduled} cron 可配置（默认每 3 分钟），staging 为空时直接跳过</li>
 *   <li>ShedLock {@code @SchedulerLock} 保证多实例部署时同一时刻仅一个实例执行结算。
 *       锁最多持有 5 分钟（防实例崩溃后锁不释放），至少持有 30 秒（防调度抖动重复执行），
 *       锁状态由 ShedLockConfig 的 JdbcTemplateLockProvider 持久化到 shedlock 表</li>
 * </ul></p>
 *
 * <p>失败处理：batchClear 抛出异常时本轮结算失败，staging 已 drain（订单丢失风险由
 * ClearingEngine 幂等 + 后续对账补偿兜底）；后续可扩展为失败重入队列。</p>
 */
@Component
public class SettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementScheduler.class);

    private final SettlementEventCollector eventCollector;
    private final ClearingEngine clearingEngine;
    private final boolean settlementEnabled;

    public SettlementScheduler(SettlementEventCollector eventCollector,
                                ClearingEngine clearingEngine,
                                @Value("${nexus.settlement.enabled:true}") boolean settlementEnabled) {
        this.eventCollector = eventCollector;
        this.clearingEngine = clearingEngine;
        this.settlementEnabled = settlementEnabled;
    }

    /**
     * 定时结算主入口：默认每 3 分钟执行（cron 可配置）。
     *
     * <p>从 staging 取出 PENDING 订单打包为 SettlementBatch 调用 batchClear。
     * staging 为空时跳过，避免无意义的结算调用。</p>
     */
    @Scheduled(cron = "${nexus.settlement.cron:0 */3 * * * *}")
    @SchedulerLock(name = "settlementScheduler", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void settle() {
        if (!settlementEnabled) {
            log.debug("Settlement disabled, skip");
            return;
        }

        List<ClearingOrder> orders = eventCollector.drainStaging();
        if (orders.isEmpty()) {
            log.debug("Settlement: no pending orders in staging, skip");
            return;
        }

        log.info("Settlement started: batching {} orders", orders.size());

        SettlementBatch batch = new SettlementBatch();
        batch.setBatchNo(UUID.randomUUID().toString());
        batch.setOrders(orders);
        batch.setCurrency(inferCommonCurrency(orders));
        batch.setStatus(SettlementBatch.BatchStatus.PENDING);
        batch.setCreatedAt(Instant.now());

        try {
            SettlementBatch result = clearingEngine.batchClear(batch);
            if (result != null && result.getStatus() == SettlementBatch.BatchStatus.SETTLED) {
                log.info("Settlement completed: batchNo={}, orders={}, netAmount={}",
                        result.getBatchNo(), orders.size(), result.getSettlementAmount());
            } else {
                log.warn("Settlement batch not fully settled: batchNo={}, status={}",
                        batch.getBatchNo(), result != null ? result.getStatus() : "null");
            }
        } catch (RuntimeException e) {
            log.error("Settlement failed: batchNo={}, error={}", batch.getBatchNo(), e.getMessage(), e);
        }
    }

    /**
     * 推断批次公共币种：全部一致返回该币种，否则返回 "MIXED"。
     */
    private String inferCommonCurrency(List<ClearingOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return "NEX";
        }
        String first = orders.get(0).getCurrency();
        for (ClearingOrder order : orders) {
            if (order.getCurrency() != null && !order.getCurrency().equals(first)) {
                return "MIXED";
            }
        }
        return first != null ? first : "NEX";
    }
}
