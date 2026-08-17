package org.nexus.gateway.execution;

import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.model.Refund;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.repository.RefundRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时对账任务（P2-F3 事务边界补偿模式重设计）。
 *
 * <p>每 5 分钟（可配置）执行一次，完成以下三项工作：</p>
 *
 * <h3>1. 扫描 PENDING 超时记录，调用 CompensationService 处理</h3>
 * <p>查询创建时间超过 {@code pending-timeout-minutes} 仍为 PENDING 的退款记录，
 * 交由 {@link CompensationService#handlePendingRefunds} 处理：
 * 已上链 → COMPLETED，未上链 → FAILED + 补偿。</p>
 *
 * <h3>2. 对账：比较数据库记录与链上实际状态</h3>
 * <ul>
 *   <li>数据库 COMPLETED 但链上未确认 → 标记为 {@code RECONCILIATION_NEEDED}，
 *       等待人工介入（避免基于错误状态做后续业务决策）</li>
 *   <li>数据库 PENDING 但链上已确认 → 更新为 COMPLETED（阶段3 漏更新的修正）</li>
 * </ul>
 *
 * <h3>3. 生成对账报告</h3>
 * <p>本次对账的统计信息（扫描数 / 修正数 / 异常数）写入日志，
 * 供监控告警系统采集。可选扩展为持久化到 {@code reconciliation_reports} 表。</p>
 *
 * <h3>不阻塞正常交易流程</h3>
 * <p>本任务通过 {@code @Scheduled} 在 Spring scheduling 线程池执行，
 * 与正常交易的 HTTP 请求线程池隔离。每条记录处理使用
 * {@code @Transactional(REQUIRES_NEW)} 独立事务，避免长事务占用连接。
 * 单次对账处理记录数上限由 {@code reconciliation-batch-size} 控制。</p>
 *
 * <h3>幂等性</h3>
 * <p>对账任务可重复执行：状态修正前再次校验当前状态，
 * 已被其他线程/实例处理的记录直接跳过。</p>
 */
@Component
public class ReconciliationTask {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationTask.class);

    private final ExecutionConfig executionConfig;
    private final CompensationService compensationService;
    private final RefundRepository refundRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final ChainRpcClient chainRpcClient;

    public ReconciliationTask(ExecutionConfig executionConfig,
                              CompensationService compensationService,
                              RefundRepository refundRepository,
                              PaymentOrderRepository paymentOrderRepository,
                              ChainRpcClient chainRpcClient) {
        this.executionConfig = executionConfig;
        this.compensationService = compensationService;
        this.refundRepository = refundRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.chainRpcClient = chainRpcClient;
    }

    /**
     * 定时对账主入口：每 5 分钟执行（cron 可配置）。
     *
     * <p>cron 表达式由 {@link ExecutionConfig#getReconciliationCron()} 提供，
     * 默认为每 5 分钟执行一次（Spring 6 六字段格式：秒 分 时 日 月 周）。</p>
     */
    @Scheduled(cron = "${nexus.gatewayservice.execution.reconciliation-cron:0 */5 * * * *}")
    public void reconcile() {
        if (!executionConfig.isEnabled()) {
            log.debug("Reconciliation disabled, skip");
            return;
        }

        LocalDateTime startedAt = LocalDateTime.now();
        log.info("Reconciliation started at {}", startedAt);

        ReconciliationReport report = new ReconciliationReport();

        try {
            // 1. 处理 PENDING 超时记录（补偿）
            int compensated = handlePendingTimeouts();
            report.pendingCompensated = compensated;

            // 2. 对账：COMPLETED 但链上未确认 / PENDING 但链上已确认
            reconcileCompletedRefunds(report);
            reconcilePendingRefunds(report);

            // 3. 生成对账报告
            report.completedAt = LocalDateTime.now();
            log.info("Reconciliation report: {}", report);
        } catch (Exception e) {
            log.error("Reconciliation failed: {}", e.getMessage(), e);
        }
    }

    /**
     * 步骤1：处理 PENDING 超时记录。
     *
     * @return 处理的记录数
     */
    private int handlePendingTimeouts() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minusMinutes(executionConfig.getPendingTimeoutMinutes());
        log.info("Handling pending refunds created before {} (timeout={}min)",
                cutoff, executionConfig.getPendingTimeoutMinutes());
        return compensationService.handlePendingRefunds(cutoff);
    }

    /**
     * 步骤2a：对账 COMPLETED 退款——验证链上是否确实已确认。
     *
     * <p>数据库标记 COMPLETED 但链上未确认 → 标记为 RECONCILIATION_NEEDED。
     * 这种情况通常发生在阶段3 错误地标记为 COMPLETED 但链上交易实际未上链
     * （如签名服务返回了错误的 txHash）。</p>
     */
    private void reconcileCompletedRefunds(ReconciliationReport report) {
        List<Refund> completedRefunds = refundRepository.findByStatus(Refund.RefundStatus.COMPLETED);
        int batchSize = Math.min(completedRefunds.size(), executionConfig.getReconciliationBatchSize());

        for (int i = 0; i < batchSize; i++) {
            Refund refund = completedRefunds.get(i);
            try {
                verifyCompletedRefund(refund, report);
            } catch (Exception e) {
                log.error("Reconcile completed refund {} failed: {}",
                        refund.getRefundNo(), e.getMessage());
                report.errors++;
            }
        }
    }

    /**
     * 验证单条 COMPLETED 退款的链上状态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void verifyCompletedRefund(Refund refund, ReconciliationReport report) {
        if (refund.getChainTxHash() == null || refund.getChainTxHash().isEmpty()) {
            // COMPLETED 但无 chainTxHash：数据异常，标记需要人工对账
            refund.setStatus(Refund.RefundStatus.RECONCILIATION_NEEDED);
            refundRepository.save(refund);
            report.reconciliationNeeded++;
            log.warn("Refund {} marked RECONCILIATION_NEEDED (COMPLETED but no chainTxHash)",
                    refund.getRefundNo());
            return;
        }

        boolean confirmed = queryChainConfirmation(refund.getChainTxHash());
        if (!confirmed) {
            // 数据库 COMPLETED 但链上未确认 → 标记为 RECONCILIATION_NEEDED
            refund.setStatus(Refund.RefundStatus.RECONCILIATION_NEEDED);
            refundRepository.save(refund);
            report.reconciliationNeeded++;
            log.warn("Refund {} marked RECONCILIATION_NEEDED (COMPLETED but not confirmed on chain, txHash={})",
                    refund.getRefundNo(), refund.getChainTxHash());
        } else {
            report.verifiedOk++;
        }
    }

    /**
     * 步骤2b：对账 PENDING 退款——检查链上是否已确认（阶段3 漏更新的修正）。
     *
     * <p>数据库 PENDING 但链上已确认 → 更新为 COMPLETED。
     * 这种情况发生在阶段3 因故障未执行，但阶段2 链上交易已成功上链。</p>
     */
    private void reconcilePendingRefunds(ReconciliationReport report) {
        List<Refund> pendingRefunds = refundRepository.findByStatus(Refund.RefundStatus.PENDING);
        int batchSize = Math.min(pendingRefunds.size(), executionConfig.getReconciliationBatchSize());

        for (int i = 0; i < batchSize; i++) {
            Refund refund = pendingRefunds.get(i);
            try {
                verifyPendingRefund(refund, report);
            } catch (Exception e) {
                log.error("Reconcile pending refund {} failed: {}",
                        refund.getRefundNo(), e.getMessage());
                report.errors++;
            }
        }
    }

    /**
     * 验证单条 PENDING 退款的链上状态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void verifyPendingRefund(Refund refund, ReconciliationReport report) {
        if (refund.getChainTxHash() == null || refund.getChainTxHash().isEmpty()) {
            // PENDING 且无 chainTxHash：阶段2 未执行，由 CompensationService 在步骤1 处理
            return;
        }

        boolean confirmed = queryChainConfirmation(refund.getChainTxHash());
        if (confirmed) {
            // 数据库 PENDING 但链上已确认 → 更新为 COMPLETED
            refund.setStatus(Refund.RefundStatus.COMPLETED);
            refund.setCompletedAt(LocalDateTime.now());
            refundRepository.save(refund);

            // 同步更新订单状态
            paymentOrderRepository.findById(refund.getOrderId()).ifPresent(order -> {
                if (order.getStatus() == PaymentOrder.OrderStatus.REFUND_PENDING) {
                    order.setStatus(PaymentOrder.OrderStatus.REFUNDED);
                    paymentOrderRepository.save(order);
                }
            });
            report.pendingToCompleted++;
            log.info("Refund {} reconciled: PENDING → COMPLETED (chain confirmed, txHash={})",
                    refund.getRefundNo(), refund.getChainTxHash());
        }
    }

    /**
     * 查询链上交易确认状态（容错）。
     */
    private boolean queryChainConfirmation(String chainTxHash) {
        try {
            return chainRpcClient.isTransactionConfirmed(chainTxHash);
        } catch (Exception e) {
            log.warn("Chain confirmation query failed for txHash={}: {}",
                    chainTxHash, e.getMessage());
            return false;
        }
    }

    /**
     * 对账报告（内部统计结构）。
     */
    static class ReconciliationReport {
        int pendingCompensated = 0;
        int verifiedOk = 0;
        int reconciliationNeeded = 0;
        int pendingToCompleted = 0;
        int errors = 0;
        LocalDateTime completedAt;

        @Override
        public String toString() {
            return "ReconciliationReport{"
                    + "pendingCompensated=" + pendingCompensated
                    + ", verifiedOk=" + verifiedOk
                    + ", reconciliationNeeded=" + reconciliationNeeded
                    + ", pendingToCompleted=" + pendingToCompleted
                    + ", errors=" + errors
                    + ", completedAt=" + completedAt + '}';
        }
    }
}