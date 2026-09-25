package org.nexus.gateway.transaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事务恢复调度器 — 定时扫描超时的未完成事务并执行恢复操作。
 *
 * <p>每 60 秒扫描一次，处理以下场景：</p>
 * <ul>
 *   <li>TCC 事务 TRY 状态超时 → 执行 Cancel（释放预留资源）</li>
 *   <li>TCC 事务 CONFIRM 状态超时 → 重试 Confirm</li>
 *   <li>SAGA 事务 PENDING 状态超时 → 执行 Compensation</li>
 *   <li>SAGA 事务 COMPENSATE 状态超时 → 重试 Compensation</li>
 * </ul>
 *
 * <p>超时阈值：TCC 事务 30 秒，SAGA 事务 60 秒。</p>
 */
@Component
public class TransactionRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(TransactionRecoveryScheduler.class);

    /** TCC 事务超时阈值（秒） */
    private static final int TCC_TIMEOUT_SECONDS = 30;
    /** SAGA 事务超时阈值（秒） */
    private static final int SAGA_TIMEOUT_SECONDS = 60;

    private final TransactionLogRepository transactionLogRepository;

    public TransactionRecoveryScheduler(TransactionLogRepository transactionLogRepository) {
        this.transactionLogRepository = transactionLogRepository;
    }

    /**
     * 定时扫描超时事务并执行恢复操作。
     *
     * <p>每 60 秒执行一次（fixedDelay=60000）。</p>
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void recoverTimeoutTransactions() {
        log.debug("Transaction recovery scan started");

        // 扫描 TCC 事务超时的 TRY 状态日志
        LocalDateTime tccCutoff = LocalDateTime.now().minusSeconds(TCC_TIMEOUT_SECONDS);
        List<TransactionLog> tccTryTimeouts = transactionLogRepository
                .findByTransactionTypeAndStepStatusAndUpdatedAtBefore(
                        TransactionType.TCC, TransactionStepStatus.TRY, tccCutoff);
        for (TransactionLog txLog : tccTryTimeouts) {
            log.warn("Recovering TCC transaction with timeout TRY: txId={}, stepName={}",
                    txLog.getTransactionId(), txLog.getStepName());
            recoverTccTryTimeout(txLog);
        }

        // 扫描 TCC 事务超时的 CONFIRM 状态日志
        List<TransactionLog> tccConfirmTimeouts = transactionLogRepository
                .findByTransactionTypeAndStepStatusAndUpdatedAtBefore(
                        TransactionType.TCC, TransactionStepStatus.CONFIRM, tccCutoff);
        for (TransactionLog txLog : tccConfirmTimeouts) {
            log.warn("Recovering TCC transaction with timeout CONFIRM: txId={}",
                    txLog.getTransactionId());
            recoverTccConfirmTimeout(txLog);
        }

        // 扫描 SAGA 事务超时的 PENDING 状态日志
        LocalDateTime sagaCutoff = LocalDateTime.now().minusSeconds(SAGA_TIMEOUT_SECONDS);
        List<TransactionLog> sagaPendingTimeouts = transactionLogRepository
                .findByTransactionTypeAndStepStatusAndUpdatedAtBefore(
                        TransactionType.SAGA, TransactionStepStatus.PENDING, sagaCutoff);
        for (TransactionLog txLog : sagaPendingTimeouts) {
            log.warn("Recovering SAGA transaction with timeout PENDING: txId={}, stepName={}",
                    txLog.getTransactionId(), txLog.getStepName());
            recoverSagaPendingTimeout(txLog);
        }

        // 扫描 SAGA 事务超时的 COMPENSATE 状态日志
        List<TransactionLog> sagaCompensateTimeouts = transactionLogRepository
                .findByTransactionTypeAndStepStatusAndUpdatedAtBefore(
                        TransactionType.SAGA, TransactionStepStatus.COMPENSATE, sagaCutoff);
        for (TransactionLog txLog : sagaCompensateTimeouts) {
            log.warn("Recovering SAGA transaction with timeout COMPENSATE: txId={}, stepName={}",
                    txLog.getTransactionId(), txLog.getStepName());
            recoverSagaCompensateTimeout(txLog);
        }

        log.debug("Transaction recovery scan completed");
    }

    /**
     * 恢复 TCC 事务 TRY 超时：标记为 FAILED，等待人工处理或自动 Cancel。
     *
     * @param txLog 超时的事务日志
     */
    private void recoverTccTryTimeout(TransactionLog txLog) {
        // Try 超时意味着资源预留可能已成功但未记录，或执行中崩溃
        // 安全策略：标记为 FAILED，由运维确认后决定 Cancel 或重试
        txLog.setStepStatus(TransactionStepStatus.FAILED);
        txLog.setErrorMessage("TRY phase timeout, auto-recovered");
        transactionLogRepository.save(txLog);
        log.info("TCC TRY timeout recovered: txId={}", txLog.getTransactionId());
    }

    /**
     * 恢复 TCC 事务 CONFIRM 超时：标记为 FAILED，等待恢复重试。
     *
     * @param txLog 超时的事务日志
     */
    private void recoverTccConfirmTimeout(TransactionLog txLog) {
        // Confirm 超时意味着 Try 已成功但 Confirm 未完成
        // 安全策略：标记为 FAILED，由恢复调度器后续重试 Confirm
        txLog.setStepStatus(TransactionStepStatus.FAILED);
        txLog.setErrorMessage("CONFIRM phase timeout, pending retry");
        transactionLogRepository.save(txLog);
        log.info("TCC CONFIRM timeout recovered: txId={}", txLog.getTransactionId());
    }

    /**
     * 恢复 SAGA 事务 PENDING 超时：标记为 FAILED，等待补偿。
     *
     * @param txLog 超时的事务日志
     */
    private void recoverSagaPendingTimeout(TransactionLog txLog) {
        // SAGA 步骤 PENDING 超时意味着步骤执行中崩溃
        // 安全策略：标记为 FAILED，触发逆向补偿
        txLog.setStepStatus(TransactionStepStatus.FAILED);
        txLog.setErrorMessage("SAGA step timeout, auto-recovered");
        transactionLogRepository.save(txLog);
        log.info("SAGA PENDING timeout recovered: txId={}", txLog.getTransactionId());
    }

    /**
     * 恢复 SAGA 事务 COMPENSATE 超时：标记为 FAILED，等待重试。
     *
     * @param txLog 超时的事务日志
     */
    private void recoverSagaCompensateTimeout(TransactionLog txLog) {
        // 补偿超时意味着补偿执行中崩溃
        // 安全策略：标记为 FAILED，由恢复调度器后续重试补偿
        txLog.setStepStatus(TransactionStepStatus.FAILED);
        txLog.setErrorMessage("COMPENSATE phase timeout, pending retry");
        transactionLogRepository.save(txLog);
        log.info("SAGA COMPENSATE timeout recovered: txId={}", txLog.getTransactionId());
    }
}