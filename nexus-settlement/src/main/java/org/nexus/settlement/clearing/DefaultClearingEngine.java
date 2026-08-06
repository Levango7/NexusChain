package org.nexus.settlement.clearing;

import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.SandboxOnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 默认清结算引擎实现。
 * <p>
 * 账本级清结算：对批次内清算订单逐笔推进结算状态并汇总净额；
 * 每笔结算同时通过 {@link Ledger} 完成复式记账（借：待结算负债，贷：商户可用余额），
 * 并通过 {@link OnChainExecutionChannel} 发起链上结算转账。
 * </p>
 *
 * <p>链上执行通道通过 Spring 注入；当上层应用（nexus-gateway）提供真实实现时
 * 使用真实通道，否则 fallback 到 {@link SandboxOnChainExecutionChannel}。</p>
 */
@Service
public class DefaultClearingEngine implements ClearingEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultClearingEngine.class);

    /** 平台结算热钱包地址默认值 */
    private static final String DEFAULT_PLATFORM_SETTLEMENT_ADDRESS = "PLATFORM_HOT_WALLET";

    /** 账本组件 */
    private final Ledger ledger;

    /** 对账服务（可选注入，用于触发对账） */
    private final org.nexus.settlement.reconciliation.ReconciliationService reconciliationService;

    /** 链上执行通道 */
    private final OnChainExecutionChannel executionChannel;

    /** 平台结算热钱包链上地址 */
    private final String platformSettlementAddress;

    public DefaultClearingEngine(Ledger ledger,
                                 org.nexus.settlement.reconciliation.ReconciliationService reconciliationService) {
        this(ledger, reconciliationService, new SandboxOnChainExecutionChannel(),
                DEFAULT_PLATFORM_SETTLEMENT_ADDRESS);
    }

    @Autowired
    public DefaultClearingEngine(Ledger ledger,
                                 org.nexus.settlement.reconciliation.ReconciliationService reconciliationService,
                                 OnChainExecutionChannel executionChannel,
                                 @Value("${nexus.settlement.platform-address:PLATFORM_HOT_WALLET}") String platformSettlementAddress) {
        this.ledger = ledger;
        this.reconciliationService = reconciliationService;
        this.executionChannel = executionChannel;
        this.platformSettlementAddress = platformSettlementAddress != null && !platformSettlementAddress.isEmpty()
                ? platformSettlementAddress : DEFAULT_PLATFORM_SETTLEMENT_ADDRESS;
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
        // 链上结算转账：通过统一执行通道发起
        TransactionRequest request = new TransactionRequest(
                TransactionRequest.Type.SETTLEMENT,
                platformSettlementAddress,
                "MERCHANT:" + order.getMerchantId(),
                order.getAmount(),
                order.getCurrency(),
                "settlement:" + order.getOrderId(),
                order.getOrderId());
        TransactionResult result = executionChannel.execute(request);
        if (result != null && result.isSuccess()) {
            order.setStatus(ClearingOrder.OrderStatus.SETTLED);
            log.info("settle on-chain success: orderId={}, txHash={}, simulated={}",
                    order.getOrderId(), result.getTxHash(), result.isSimulated());
        } else {
            order.setStatus(ClearingOrder.OrderStatus.FAILED);
            String err = result != null ? result.getError() : "execution channel returned null";
            log.error("settle on-chain failed: orderId={}, error={}", order.getOrderId(), err);
        }
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
