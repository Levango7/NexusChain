package org.nexus.settlement.clearing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 默认清结算引擎实现。
 * <p>
 * 账本级清结算：对批次内清算订单逐笔推进结算状态并汇总净额；
 * 每笔结算同时通过 {@link Ledger} 完成复式记账（借：待结算负债，贷：商户可用余额）。
 * 链上结算转账仍为 TODO，需在接入链上执行后补充。
 * </p>
 */
@Service
public class DefaultClearingEngine implements ClearingEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultClearingEngine.class);

    /** 账本组件 */
    private final Ledger ledger;

    /** 对账服务（可选注入，用于触发对账） */
    private final org.nexus.settlement.reconciliation.ReconciliationService reconciliationService;

    public DefaultClearingEngine(Ledger ledger,
                                 org.nexus.settlement.reconciliation.ReconciliationService reconciliationService) {
        this.ledger = ledger;
        this.reconciliationService = reconciliationService;
    }

    @Override
    public SettlementBatch batchClear(SettlementBatch batch) {
        if (batch == null) {
            return null;
        }
        List<ClearingOrder> orders = batch.getOrders() != null ? batch.getOrders() : List.of();
        if (orders.isEmpty()) {
            log.warn("batchClear received empty batch: batchNo={}", batch.getBatchNo());
            batch.setStatus(SettlementBatch.BatchStatus.FAILED);
            return batch;
        }

        BigDecimal netAmount = BigDecimal.ZERO;
        for (ClearingOrder order : orders) {
            ClearingOrder settled = settle(order);
            if (settled.getStatus() == ClearingOrder.OrderStatus.SETTLED && settled.getAmount() != null) {
                netAmount = netAmount.add(settled.getAmount());
            }
        }

        batch.setSettlementAmount(netAmount);
        batch.setStatus(SettlementBatch.BatchStatus.SETTLED);
        log.info("batchClear completed: batchNo={}, orders={}, netAmount={}",
                batch.getBatchNo(), orders.size(), netAmount);
        return batch;
    }

    @Override
    public ClearingOrder settle(ClearingOrder order) {
        if (order == null) {
            return null;
        }
        if (order.getStatus() == ClearingOrder.OrderStatus.SETTLED) {
            return order;
        }
        if (order.getAmount() == null || order.getMerchantId() == null) {
            order.setStatus(ClearingOrder.OrderStatus.FAILED);
            return order;
        }
        // 账户级落账：借：待结算负债，贷：商户可用余额
        ledger.bookSettlement(order.getMerchantId(), order.getAmount(), order.getOrderId());
        // TODO: 链上结算转账（接入链上执行后补充）
        order.setStatus(ClearingOrder.OrderStatus.SETTLED);
        return order;
    }

    @Override
    public org.nexus.settlement.reconciliation.ReconciliationReport reconcile(
            org.nexus.settlement.reconciliation.ReconciliationReport report) {
        // 触发链上对账，产出真实比对报告
        org.nexus.settlement.reconciliation.ReconciliationReport chainReport =
                reconciliationService.reconcileWithChain();
        // 将链上对账差错并入输入报告
        if (chainReport != null && chainReport.getDiscrepancyCount() > 0) {
            java.util.List<String> merged = new java.util.ArrayList<>();
            if (report != null && report.getDiscrepancies() != null) {
                merged.addAll(report.getDiscrepancies());
            }
            merged.addAll(chainReport.getDiscrepancies());
            report = report != null ? report : new org.nexus.settlement.reconciliation.ReconciliationReport();
            report.setDiscrepancies(merged);
            report.setDiscrepancyCount(merged.size());
            report.setMatchedCount(chainReport.getMatchedCount());
            report.setReconcileDate(chainReport.getReconcileDate());
        }
        return reconciliationService.reportDiscrepancy(report != null ? report : chainReport);
    }
}
