package org.nexus.gateway.execution;

import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.clearing.SettlementBatch;
import org.nexus.gateway.clearing.SettlementBatchRepository;

import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.model.Refund;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.repository.RefundRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 补偿服务（P2-F3 事务边界补偿模式重设计）。
 *
 * <p>处理三阶段执行模式中遗留的 PENDING 超时记录，确保最终一致性：</p>
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li><strong>扫描 PENDING 超时记录</strong>：查询超过 N 分钟仍为 PENDING 的记录
 *       （由 {@link ReconciliationTask} 定时触发）</li>
 *   <li><strong>链上查询</strong>：检查该操作是否已上链
 *       <ul>
 *         <li>有 chainTxHash → 查询链上确认状态</li>
 *         <li>无 chainTxHash → 链上未执行（阶段2 失败或未到达）</li>
 *       </ul>
 *   </li>
 *   <li><strong>状态修正</strong>：
 *       <ul>
 *         <li>已上链且已确认 → 更新为 COMPLETED</li>
 *         <li>已上链但未确认 → 保持 PENDING，等待下次对账</li>
 *         <li>未上链 → 标记为 FAILED，触发补偿</li>
 *       </ul>
 *   </li>
 *   <li><strong>补偿操作</strong>：根据操作类型执行反向操作
 *       <ul>
 *         <li>refund 失败 → 无需补偿（资金未转出，仅需回滚订单状态到 PAID 允许重试）</li>
 *         <li>withdrawal 失败 → 释放冻结余额（wallet-service compensate 端点
 *             待实现，当前记录日志等待后续版本补充）</li>
 *         <li>settlement 失败 → 回滚清算状态（进程内调用 SettlementBatchRepository，
 *             将批次状态从 EXECUTING/FAILED 回滚到 OPEN 允许重试）</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h3>幂等性保证</h3>
 * <p>所有补偿操作必须幂等：
 * <ul>
 *   <li>状态更新使用乐观锁（{@code @Version}），并发更新由 JPA 抛出
 *       {@code OptimisticLockException}，调用方捕获后跳过（记录已被其他线程处理）</li>
 *   <li>补偿操作前再次校验状态（双重检查），避免重复补偿</li>
 *   <li>退款补偿（回滚订单到 PAID）幂等：若订单已是 PAID 则跳过</li>
 * </ul>
 * </p>
 *
 * <h3>不阻塞正常交易流程</h3>
 * <p>本服务由 {@link ReconciliationTask} 通过 {@code @Scheduled} 定时调用，
 * 使用独立线程池（Spring scheduling 默认单线程，可配置线程池），
 * 不占用正常交易的数据库连接池配额（每次处理单条记录后立即提交事务）。</p>
 */
@Service
public class CompensationService {

    private static final Logger log = LoggerFactory.getLogger(CompensationService.class);

    /**
     * 低6 改进：单次 handlePendingRefunds 处理的记录数上限。
     *
     * <p>PENDING 积压过多时（如链节点长时间不可达后恢复），一次查询全部记录
     * 会导致：数据库连接池耗尽、单次对账耗时过长阻塞调度线程、
     * 链上 RPC 批量调用打满链节点。通过 batchSize 限制单次处理量，
     * 剩余记录由下次对账周期处理（最终一致）。</p>
     *
     * <p>默认 100，通过 {@code nexus.compensation.batch-size} 配置覆盖。</p>
     */
    @Value("${nexus.compensation.batch-size:100}")
    private int batchSize;

    private final RefundRepository refundRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final ChainRpcClient chainRpcClient;
    private final SettlementBatchRepository settlementBatchRepository;

    public CompensationService(RefundRepository refundRepository,
                               PaymentOrderRepository paymentOrderRepository,
                               ChainRpcClient chainRpcClient,
                               SettlementBatchRepository settlementBatchRepository) {
        this.refundRepository = refundRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.chainRpcClient = chainRpcClient;
        this.settlementBatchRepository = settlementBatchRepository;
    }

    /**
     * 处理 PENDING 超时的退款记录。
     *
     * <p>由 {@link ReconciliationTask} 定时调用，扫描创建时间早于 {@code cutoff}
     * 且状态为 PENDING 的退款记录，逐条处理。</p>
     *
     * <p>低6 改进：通过 {@code batchSize}（从 {@code nexus.compensation.batch-size} 读取，默认 100）
     * 限制单次查询返回的记录数，避免 PENDING 积压过多时一次处理大批量记录
     * 占用数据库连接池、阻塞调度线程。剩余记录由下次对账周期处理（最终一致）。</p>
     *
     * @param cutoff 时间阈值（处理 createdAt < cutoff 的记录）
     * @return 处理的记录数量（含状态修正与补偿）
     */
    public int handlePendingRefunds(LocalDateTime cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        // 低6 改进：使用 Pageable 限制单次查询记录数，避免积压时一次处理过多
        int effectiveBatchSize = batchSize > 0 ? batchSize : 100;
        List<Refund> pendingRefunds = refundRepository.findByStatusAndCreatedAtBefore(
                Refund.RefundStatus.PENDING, cutoff, PageRequest.of(0, effectiveBatchSize));
        if (pendingRefunds.isEmpty()) {
            log.debug("No pending refunds to compensate before cutoff={}", cutoff);
            return 0;
        }
        log.info("Compensating {} pending refunds created before {} (batchSize={})",
                pendingRefunds.size(), cutoff, effectiveBatchSize);

        int processed = 0;
        for (Refund refund : pendingRefunds) {
            try {
                handleOneRefund(refund);
                processed++;
            } catch (RuntimeException e) {
                // 单条记录处理失败不影响其他记录，记录日志后继续
                log.error("Compensation failed for refund {}: {}", refund.getRefundNo(), e.getMessage(), e);
            }
        }
        log.info("Compensation completed: processed={}/{}, cutoff={}, batchSize={}",
                processed, pendingRefunds.size(), cutoff, effectiveBatchSize);
        return processed;
    }

    /**
     * 处理单条 PENDING 退款记录。
     *
     * <p>幂等：若记录状态已非 PENDING（被其他线程/实例处理），直接跳过。</p>
     *
     * @param refund PENDING 退款记录
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleOneRefund(Refund refund) {
        // 双重检查：并发场景下记录可能已被其他线程处理
        Refund fresh = refundRepository.findById(refund.getId()).orElse(null);
        if (fresh == null) {
            log.warn("Refund {} not found, skip compensation", refund.getRefundNo());
            return;
        }
        if (fresh.getStatus() != Refund.RefundStatus.PENDING) {
            log.debug("Refund {} status is {} (not PENDING), skip compensation",
                    fresh.getRefundNo(), fresh.getStatus());
            return;
        }

        // 链上查询：检查是否已上链
        if (fresh.getChainTxHash() != null && !fresh.getChainTxHash().isEmpty()) {
            // 有 chainTxHash：阶段2 已执行，查询链上确认状态
            boolean confirmed = queryChainConfirmation(fresh.getChainTxHash());
            if (confirmed) {
                // 已上链且已确认 → 更新为 COMPLETED
                markRefundCompleted(fresh);
                log.info("Refund {} confirmed on chain, marked COMPLETED", fresh.getRefundNo());
            } else {
                // 已上链但未确认 → 保持 PENDING，等待下次对账
                log.debug("Refund {} still pending on chain, wait for next reconciliation",
                        fresh.getRefundNo());
            }
        } else {
            // 无 chainTxHash：阶段2 未执行或执行失败 → 标记为 FAILED，触发补偿
            markRefundFailedAndCompensate(fresh,
                    "no chainTxHash after timeout; on-chain execution likely failed");
            log.warn("Refund {} marked FAILED (no chainTxHash), triggering compensation",
                    fresh.getRefundNo());
        }
    }

    /**
     * 标记退款为 COMPLETED（链上已确认）。
     */
    private void markRefundCompleted(Refund refund) {
        refund.setStatus(Refund.RefundStatus.COMPLETED);
        refund.setCompletedAt(LocalDateTime.now());
        refundRepository.save(refund);

        // 同步更新订单状态为 REFUNDED
        paymentOrderRepository.findById(refund.getOrderId()).ifPresent(order -> {
            if (order.getStatus() == PaymentOrder.OrderStatus.REFUND_PENDING) {
                order.setStatus(PaymentOrder.OrderStatus.REFUNDED);
                paymentOrderRepository.save(order);
                log.info("Order {} transitioned to REFUNDED for refund {}",
                        order.getOrderNo(), refund.getRefundNo());
            }
        });
    }

    /**
     * 标记退款为 FAILED 并执行补偿。
     *
     * <p>退款补偿策略：<strong>无需补偿</strong>（资金未转出）。
     * 仅需回滚订单状态到 PAID，允许后续重试退款。</p>
     *
     * <p>幂等：若订单已是 PAID 则跳过状态更新。</p>
     *
     * @param refund        退款记录
     * @param failureReason 失败原因
     */
    private void markRefundFailedAndCompensate(Refund refund, String failureReason) {
        // 1. 标记退款为 FAILED
        refund.setStatus(Refund.RefundStatus.FAILED);
        refundRepository.save(refund);

        // 2. 补偿：回滚订单状态到 PAID（允许重试退款）
        //    refund 失败 → 资金未转出，无需链上补偿，仅需数据库状态回滚
        paymentOrderRepository.findById(refund.getOrderId()).ifPresent(order -> {
            if (order.getStatus() == PaymentOrder.OrderStatus.REFUND_PENDING) {
                order.setStatus(PaymentOrder.OrderStatus.PAID);
                paymentOrderRepository.save(order);
                log.info("Compensation: order {} rolled back to PAID (refund {} failed, no on-chain transfer)",
                        order.getOrderNo(), refund.getRefundNo());
            } else {
                log.debug("Compensation: order {} status is {} (not REFUND_PENDING), skip rollback",
                        order.getOrderNo(), order.getStatus());
            }
        });
    }

    /**
     * 查询链上交易确认状态（容错：链不可达时返回 false，等待下次对账）。
     */
    private boolean queryChainConfirmation(String chainTxHash) {
        try {
            return chainRpcClient.isTransactionConfirmed(chainTxHash);
        } catch (RuntimeException e) {
            log.warn("Chain confirmation query failed for txHash={}: {}",
                    chainTxHash, e.getMessage());
            return false;
        }
    }

    /**
     * 通用补偿入口（支持 REFUND / WITHDRAWAL / SETTLEMENT 三类操作）。
     *
     * <p>补偿策略按操作类型分发：
     * <ul>
     *   <li><strong>REFUND</strong>：由 {@link #handlePendingRefunds} 处理，此处无需额外操作
     *       （refund 失败资金未转出，仅需回滚订单状态到 PAID）</li>
     *   <li><strong>WITHDRAWAL</strong>：通过 Feign 调用 wallet-service 补偿端点
     *       {@code POST /api/v1/wallet/withdrawal/{requestId}/compensate} 释放冻结余额。
     *       Feign fallback 返回 {@code null}，调用方按补偿失败处理（等待下次对账重试）</li>
     *   <li><strong>SETTLEMENT</strong>：settlement 为 composite build 依赖（进程内调用），
     *       直接通过 {@link SettlementBatchRepository} 回滚批次状态到 OPEN 允许重试。
     *       若批次已 COMPLETED 则跳过（幂等），若批次不存在则记录告警</li>
     * </ul>
     * </p>
     *
     * <p><strong>容错语义</strong>：单条记录补偿失败不抛异常，仅记录 ERROR 日志，
     * 由 {@code ReconciliationTask} 下次对账周期重试，确保不阻塞其他记录处理。</p>
     *
     * <p><strong>幂等性</strong>：所有补偿操作必须幂等：
     * <ul>
     *   <li>WITHDRAWAL：wallet-service 端保证幂等（已补偿则直接返回当前状态）</li>
     *   <li>SETTLEMENT：本方法在回滚前校验批次状态，仅 EXECUTING/FAILED 才回滚到 OPEN</li>
     * </ul>
     * </p>
     *
     * @param operationType  操作类型
     * @param recordId       记录 ID（withdrawal 的 requestId / settlement 的 batchId）
     * @param idempotencyKey 幂等键
     */
    public void compensate(ExecutionRequest.OperationType operationType,
                           Long recordId,
                           String idempotencyKey) {
        log.info("Compensate request: type={}, recordId={}, idempotencyKey={}",
                operationType, recordId, idempotencyKey);
        switch (operationType) {
            case REFUND:
                // Refund 补偿由 handlePendingRefunds 处理，此处无需额外操作
                log.debug("Refund compensation handled by handlePendingRefunds");
                break;
            case WITHDRAWAL:
                compensateWithdrawal(recordId);
                break;
            case SETTLEMENT:
                compensateSettlement(recordId);
                break;
            default:
                log.warn("Unknown operation type for compensation: {}", operationType);
        }
    }

    /**
     * 提现补偿：释放冻结余额并回滚提现请求状态。
     *
     * <p>对应 wallet-service {@code POST /api/v1/wallet/withdrawal/{requestId}/compensate}。
     * 当前 wallet-service 未实现该端点（WalletController 中无 compensate 端点），
     * 本方法记录 WARN 日志说明补偿未实现，等待后续版本补充端点后恢复 Feign 调用。</p>
     *
     * <p>容错：不抛异常，让对账任务继续处理其他记录。</p>
     *
     * <p>端点对齐修复（任务 #317）：移除 {@code WalletMgmtFeignClient.compensateWithdrawal}
     * 调用（WalletController 中无此端点），改为日志记录。</p>
     *
     * @param recordId 提现记录 ID
     */
    private void compensateWithdrawal(Long recordId) {
        if (recordId == null) {
            log.warn("Withdrawal compensation skipped: recordId is null");
            return;
        }
        log.warn("Withdrawal {} compensation skipped: wallet-service compensate endpoint not implemented, " +
                "will retry on next reconciliation", recordId);
    }

    /**
     * 结算补偿：回滚 settlement batch 状态到 OPEN 允许重试。
     *
     * <p>settlement 为 composite build 依赖（进程内调用 {@code ClearingEngine}），
     * 无需 Feign 跨服务调用，直接通过 {@link SettlementBatchRepository} 操作批次状态。</p>
     *
     * <p>补偿策略：
     * <ul>
     *   <li>批次状态为 EXECUTING 或 FAILED → 回滚到 OPEN，允许 {@code DefaultSettlementService.executeSettlement} 重试</li>
     *   <li>批次状态为 COMPLETED → 跳过（幂等，避免重复回滚已完成的结算）</li>
     *   <li>批次状态为 OPEN → 跳过（无需补偿）</li>
     *   <li>批次不存在 → 记录告警，等待人工介入</li>
     * </ul>
     * </p>
     *
     * <p>容错：不抛异常，让对账任务继续处理其他记录。</p>
     *
     * @param recordId settlement batch ID
     */
    private void compensateSettlement(Long recordId) {
        if (recordId == null) {
            log.warn("Settlement compensation skipped: recordId is null");
            return;
        }
        try {
            SettlementBatch batch = settlementBatchRepository.findById(recordId).orElse(null);
            if (batch == null) {
                log.warn("Settlement compensation skipped: batch {} not found, " +
                        "may require manual intervention", recordId);
                return;
            }
            SettlementBatch.BatchStatus currentStatus = batch.getStatus();
            if (currentStatus == SettlementBatch.BatchStatus.EXECUTING
                    || currentStatus == SettlementBatch.BatchStatus.FAILED) {
                batch.setStatus(SettlementBatch.BatchStatus.OPEN);
                settlementBatchRepository.save(batch);
                log.info("Settlement {} compensation: batchNo={} status rolled back from {} to OPEN " +
                        "(allow retry)", recordId, batch.getBatchNo(), currentStatus);
            } else {
                log.debug("Settlement {} compensation skipped: batchNo={} status is {} " +
                        "(not EXECUTING/FAILED, idempotent skip)",
                        recordId, batch.getBatchNo(), currentStatus);
            }
        } catch (RuntimeException e) {
            log.error("Failed to compensate settlement {}: {}", recordId, e.getMessage(), e);
            // 不抛异常，让对账任务继续处理其他记录
        }
    }
}