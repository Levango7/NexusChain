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
 *
 * <p>恢复操作通过 TccTransactionManager 和 SagaTransactionManager 的 public 恢复方法执行。
 * 如果 action/step 不在注册表中（例如服务重启后），则降级为仅标记 FAILED 状态。</p>
 */
@Component
public class TransactionRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(TransactionRecoveryScheduler.class);

    /** TCC 事务超时阈值（秒） */
    private static final int TCC_TIMEOUT_SECONDS = 30;
    /** SAGA 事务超时阈值（秒） */
    private static final int SAGA_TIMEOUT_SECONDS = 60;

    private final TransactionLogRepository transactionLogRepository;
    private final TccTransactionManager tccTransactionManager;
    private final SagaTransactionManager sagaTransactionManager;

    public TransactionRecoveryScheduler(TransactionLogRepository transactionLogRepository,
                                        TccTransactionManager tccTransactionManager,
                                        SagaTransactionManager sagaTransactionManager) {
        this.transactionLogRepository = transactionLogRepository;
        this.tccTransactionManager = tccTransactionManager;
        this.sagaTransactionManager = sagaTransactionManager;
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
     * 恢复 TCC 事务 TRY 超时：执行 Cancel 操作（释放预留资源）。
     *
     * <p>TRY 超时意味着资源预留可能已成功但未记录，或执行中崩溃。
     * 安全策略：调用 TccTransactionManager.retryCancel 执行 Cancel 操作。
     * 如果 Cancel 成功，更新状态为 SUCCESS（已取消）；如果失败，标记为 FAILED。</p>
     *
     * @param txLog 超时的事务日志
     */
    private void recoverTccTryTimeout(TransactionLog txLog) {
        boolean recovered = tccTransactionManager.retryCancel(txLog);
        if (recovered) {
            log.info("TCC TRY timeout recovered (Cancel succeeded): txId={}", txLog.getTransactionId());
        } else {
            log.warn("TCC TRY timeout recovery failed (Cancel failed or action not in registry): txId={}",
                    txLog.getTransactionId());
        }
    }

    /**
     * 恢复 TCC 事务 CONFIRM 超时：重试 Confirm 操作。
     *
     * <p>CONFIRM 超时意味着 Try 已成功但 Confirm 未完成。
     * 安全策略：调用 TccTransactionManager.retryConfirm 重试 Confirm 操作。
     * 如果 Confirm 成功，更新状态为 SUCCESS；如果失败，标记为 FAILED。</p>
     *
     * @param txLog 超时的事务日志
     */
    private void recoverTccConfirmTimeout(TransactionLog txLog) {
        boolean recovered = tccTransactionManager.retryConfirm(txLog);
        if (recovered) {
            log.info("TCC CONFIRM timeout recovered (Confirm succeeded): txId={}", txLog.getTransactionId());
        } else {
            log.warn("TCC CONFIRM timeout recovery failed (Confirm failed or action not in registry): txId={}",
                    txLog.getTransactionId());
        }
    }

    /**
     * 恢复 SAGA 事务 PENDING 超时：触发补偿流程。
     *
     * <p>SAGA 步骤 PENDING 超时意味着步骤执行中崩溃。
     * 安全策略：调用 SagaTransactionManager.retryCompensate 触发逆向补偿。</p>
     *
     * @param txLog 超时的事务日志
     */
    private void recoverSagaPendingTimeout(TransactionLog txLog) {
        boolean recovered = sagaTransactionManager.retryCompensate(txLog);
        if (recovered) {
            log.info("SAGA PENDING timeout recovered (compensation succeeded): txId={}",
                    txLog.getTransactionId());
        } else {
            log.warn("SAGA PENDING timeout recovery failed (compensation failed or steps not in registry): txId={}",
                    txLog.getTransactionId());
        }
    }

    /**
     * 恢复 SAGA 事务 COMPENSATE 超时：重试补偿操作。
     *
     * <p>补偿超时意味着补偿执行中崩溃。
     * 安全策略：调用 SagaTransactionManager.retryCompensate 重试补偿操作。</p>
     *
     * @param txLog 超时的事务日志
     */
    private void recoverSagaCompensateTimeout(TransactionLog txLog) {
        boolean recovered = sagaTransactionManager.retryCompensate(txLog);
        if (recovered) {
            log.info("SAGA COMPENSATE timeout recovered (compensation succeeded): txId={}",
                    txLog.getTransactionId());
        } else {
            log.warn("SAGA COMPENSATE timeout recovery failed (compensation failed or steps not in registry): txId={}",
                    txLog.getTransactionId());
        }
    }
}