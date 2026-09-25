package org.nexus.gateway.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TCC 事务管理器 — 编排 Try/Confirm/Cancel 三个阶段的执行。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>先写日志后执行操作 — 每个阶段执行前先持久化 TransactionLog</li>
 *   <li>幂等保证 — 通过 transactionId 去重，同一事务不会重复执行</li>
 *   <li>自动重试 — Confirm/Cancel 失败时自动重试（最多 3 次）</li>
 *   <li>故障可恢复 — 所有状态持久化到 TransactionLog，支持恢复调度器扫描恢复</li>
 * </ul>
 *
 * <p>执行流程：</p>
 * <ol>
 *   <li>Try 阶段：记录 TransactionLog(TRY) → 执行 tryAction</li>
 *   <li>Try 成功 → 记录 TransactionLog(CONFIRM) → 执行 confirmAction → 记录 TransactionLog(SUCCESS)</li>
 *   <li>Try 失败 → 记录 TransactionLog(CANCEL) → 执行 cancelAction → 记录 TransactionLog(FAILED)</li>
 * </ol>
 */
@Service
public class TccTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(TccTransactionManager.class);
    private static final int MAX_RETRY = 3;

    private final TransactionLogRepository transactionLogRepository;
    private final ObjectMapper objectMapper;

    /** TccAction 注册表：以 transactionId 为 key，供恢复调度器查找 action 执行恢复操作 */
    private final ConcurrentHashMap<String, TccAction> actionRegistry = new ConcurrentHashMap<>();

    public TccTransactionManager(TransactionLogRepository transactionLogRepository,
                                  ObjectMapper objectMapper) {
        this.transactionLogRepository = transactionLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 TCC 事务。
     *
     * <p>完整编排 Try → Confirm/Cancel 流程，所有步骤记录 TransactionLog。</p>
     *
     * @param action TCC 动作
     * @param ctx 事务上下文
     * @return {@code true} 事务成功（Try + Confirm 均成功）；{@code false} 事务失败（Try 失败或 Cancel 成功）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean execute(TccAction action, TransactionContext ctx) {
        String txId = ctx.getTransactionId();

        // 注册 action 以供恢复调度器使用（超时事务恢复时通过 transactionId 查找）
        actionRegistry.put(txId, action);

        // 幂等校验：若该事务已有 SUCCESS 日志，直接返回成功
        List<TransactionLog> existingLogs = transactionLogRepository
                .findByTransactionIdOrderByCreatedAtAsc(txId);
        for (TransactionLog existing : existingLogs) {
            if (existing.getStepStatus() == TransactionStepStatus.SUCCESS) {
                log.info("TCC transaction already completed: txId={}, skipping", txId);
                return true;
            }
            if (existing.getStepStatus() == TransactionStepStatus.FAILED) {
                log.info("TCC transaction already failed: txId={}, skipping", txId);
                return false;
            }
        }

        // === Try 阶段 ===
        TransactionLog tryLog = createLog(ctx, TransactionType.TCC, "try",
                0, TransactionStepStatus.TRY, action.getParticipantId());
        transactionLogRepository.save(tryLog);

        boolean tryResult;
        try {
            tryResult = action.tryAction(ctx);
        } catch (Exception e) {
            log.error("TCC Try phase exception: txId={}", txId, e);
            tryLog.setStepStatus(TransactionStepStatus.FAILED);
            tryLog.setErrorMessage(e.getMessage());
            transactionLogRepository.save(tryLog);
            boolean cancelResult = executeCancel(action, ctx);
            if (cancelResult) {
                actionRegistry.remove(txId);
            }
            return false;
        }

        if (!tryResult) {
            log.warn("TCC Try phase failed: txId={}", txId);
            tryLog.setStepStatus(TransactionStepStatus.FAILED);
            transactionLogRepository.save(tryLog);
            boolean cancelResult = executeCancel(action, ctx);
            if (cancelResult) {
                actionRegistry.remove(txId);
            }
            return false;
        }

        tryLog.setStepStatus(TransactionStepStatus.SUCCESS);
        transactionLogRepository.save(tryLog);

        // === Confirm 阶段 ===
        boolean result = executeConfirm(action, ctx);
        // 事务正常完成后从注册表中移除 action（超时事务的 action 保留供恢复调度器使用）
        if (result) {
            actionRegistry.remove(txId);
        }
        return result;
    }

    /**
     * 执行 Confirm 阶段（含重试逻辑）。
     *
     * @param action TCC 动作
     * @param ctx 事务上下文
     * @return {@code true} Confirm 成功
     */
    private boolean executeConfirm(TccAction action, TransactionContext ctx) {
        String txId = ctx.getTransactionId();

        TransactionLog confirmLog = createLog(ctx, TransactionType.TCC, "confirm",
                1, TransactionStepStatus.CONFIRM, action.getParticipantId());
        transactionLogRepository.save(confirmLog);

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                action.confirmAction(ctx);
                confirmLog.setStepStatus(TransactionStepStatus.SUCCESS);
                transactionLogRepository.save(confirmLog);

                // 记录事务整体成功
                TransactionLog successLog = createLog(ctx, TransactionType.TCC, "completed",
                        2, TransactionStepStatus.SUCCESS, action.getParticipantId());
                transactionLogRepository.save(successLog);

                log.info("TCC transaction completed: txId={}", txId);
                return true;
            } catch (Exception e) {
                log.warn("TCC Confirm phase failed (attempt {}/{}): txId={}, error={}",
                        attempt, MAX_RETRY, txId, e.getMessage());
                if (attempt == MAX_RETRY) {
                    confirmLog.setStepStatus(TransactionStepStatus.FAILED);
                    confirmLog.setErrorMessage(e.getMessage());
                    transactionLogRepository.save(confirmLog);
                    // Confirm 失败后不执行 Cancel（Try 已成功，Cancel 无法回滚已预留资源）
                    // 标记为 FAILED，由恢复调度器后续重试 Confirm
                    // action 保留在注册表中供恢复调度器使用
                    log.error("TCC Confirm phase exhausted retries: txId={}", txId);
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 执行 Cancel 阶段（含重试逻辑）。
     *
     * @param action TCC 动作
     * @param ctx 事务上下文
     * @return {@code true} Cancel 成功；{@code false} Cancel 失败
     */
    private boolean executeCancel(TccAction action, TransactionContext ctx) {
        String txId = ctx.getTransactionId();

        TransactionLog cancelLog = createLog(ctx, TransactionType.TCC, "cancel",
                1, TransactionStepStatus.CANCEL, action.getParticipantId());
        transactionLogRepository.save(cancelLog);

        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                action.cancelAction(ctx);
                cancelLog.setStepStatus(TransactionStepStatus.SUCCESS);
                transactionLogRepository.save(cancelLog);

                // 记录事务整体失败（已补偿）
                TransactionLog failedLog = createLog(ctx, TransactionType.TCC, "cancelled",
                        2, TransactionStepStatus.FAILED, action.getParticipantId());
                transactionLogRepository.save(failedLog);

                log.info("TCC transaction cancelled: txId={}", txId);
                return true;
            } catch (Exception e) {
                log.warn("TCC Cancel phase failed (attempt {}/{}): txId={}, error={}",
                        attempt, MAX_RETRY, txId, e.getMessage());
                if (attempt == MAX_RETRY) {
                    cancelLog.setStepStatus(TransactionStepStatus.FAILED);
                    cancelLog.setErrorMessage(e.getMessage());
                    transactionLogRepository.save(cancelLog);
                    // Cancel 失败由恢复调度器后续重试
                    log.error("TCC Cancel phase exhausted retries: txId={}", txId);
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * 创建事务日志记录。
     */
    private TransactionLog createLog(TransactionContext ctx, TransactionType type,
                                     String stepName, int stepIndex,
                                     TransactionStepStatus status, String participantId) {
        TransactionLog logEntry = new TransactionLog();
        logEntry.setTransactionId(ctx.getTransactionId());
        logEntry.setTransactionType(type);
        logEntry.setStepName(stepName);
        logEntry.setStepIndex(stepIndex);
        logEntry.setStepStatus(status);
        logEntry.setParticipantId(participantId);
        logEntry.setTenantId(ctx.getTenantId());

        // 业务凭证从 payload 中提取
        Object businessRef = ctx.get("businessReference");
        logEntry.setBusinessReference(businessRef != null ? businessRef.toString() : ctx.getTransactionId());

        // 序列化 payload 为 JSON
        if (ctx.getPayload() != null) {
            try {
                logEntry.setPayload(objectMapper.writeValueAsString(ctx.getPayload()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize payload for txId={}: {}", ctx.getTransactionId(), e.getMessage());
            }
        }

        return logEntry;
    }

    // === 恢复调度器调用的 public 方法 ===

    /**
     * 重试 Confirm 操作（供 TransactionRecoveryScheduler 调用）。
     *
     * <p>当 TCC 事务的 CONFIRM 阶段超时后，恢复调度器调用此方法重试 Confirm。
     * 从 actionRegistry 中查找原始 TccAction，重建事务上下文，执行 confirmAction。</p>
     *
     * @param txLog 超时的事务日志
     * @return {@code true} 恢复成功；{@code false} 恢复失败（action 不在注册表中或 confirm 执行失败）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryConfirm(TransactionLog txLog) {
        String txId = txLog.getTransactionId();
        TccAction action = actionRegistry.get(txId);
        if (action == null) {
            log.warn("TCC retryConfirm: action not found in registry (service may have restarted), txId={}", txId);
            txLog.setStepStatus(TransactionStepStatus.FAILED);
            txLog.setErrorMessage("CONFIRM retry failed: action not in registry");
            transactionLogRepository.save(txLog);
            return false;
        }

        TransactionContext ctx = rebuildContext(txLog);
        try {
            action.confirmAction(ctx);
            txLog.setStepStatus(TransactionStepStatus.SUCCESS);
            transactionLogRepository.save(txLog);

            // 记录事务整体成功
            TransactionLog successLog = createLog(ctx, TransactionType.TCC, "completed",
                    2, TransactionStepStatus.SUCCESS, action.getParticipantId());
            transactionLogRepository.save(successLog);

            // 恢复成功后从注册表中移除 action
            actionRegistry.remove(txId);
            log.info("TCC retryConfirm succeeded: txId={}", txId);
            return true;
        } catch (Exception e) {
            log.warn("TCC retryConfirm failed: txId={}, error={}", txId, e.getMessage());
            txLog.setStepStatus(TransactionStepStatus.FAILED);
            txLog.setErrorMessage("CONFIRM retry failed: " + e.getMessage());
            transactionLogRepository.save(txLog);
            return false;
        }
    }

    /**
     * 重试 Cancel 操作（供 TransactionRecoveryScheduler 调用）。
     *
     * <p>当 TCC 事务的 TRY 阶段超时后，恢复调度器调用此方法执行 Cancel（释放预留资源）。
     * 从 actionRegistry 中查找原始 TccAction，重建事务上下文，执行 cancelAction。</p>
     *
     * @param txLog 超时的事务日志
     * @return {@code true} 恢复成功；{@code false} 恢复失败
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryCancel(TransactionLog txLog) {
        String txId = txLog.getTransactionId();
        TccAction action = actionRegistry.get(txId);
        if (action == null) {
            log.warn("TCC retryCancel: action not found in registry (service may have restarted), txId={}", txId);
            txLog.setStepStatus(TransactionStepStatus.FAILED);
            txLog.setErrorMessage("CANCEL retry failed: action not in registry");
            transactionLogRepository.save(txLog);
            return false;
        }

        TransactionContext ctx = rebuildContext(txLog);
        try {
            action.cancelAction(ctx);
            txLog.setStepStatus(TransactionStepStatus.SUCCESS);
            transactionLogRepository.save(txLog);

            // 记录事务整体失败（已补偿）
            TransactionLog failedLog = createLog(ctx, TransactionType.TCC, "cancelled",
                    2, TransactionStepStatus.FAILED, action.getParticipantId());
            transactionLogRepository.save(failedLog);

            // 恢复成功后从注册表中移除 action
            actionRegistry.remove(txId);
            log.info("TCC retryCancel succeeded: txId={}", txId);
            return true;
        } catch (Exception e) {
            log.warn("TCC retryCancel failed: txId={}, error={}", txId, e.getMessage());
            txLog.setStepStatus(TransactionStepStatus.FAILED);
            txLog.setErrorMessage("CANCEL retry failed: " + e.getMessage());
            transactionLogRepository.save(txLog);
            return false;
        }
    }

    /**
     * 从 TransactionLog 重建 TransactionContext。
     *
     * @param txLog 事务日志
     * @return 重建的事务上下文
     */
    private TransactionContext rebuildContext(TransactionLog txLog) {
        Map<String, Object> payload = null;
        if (txLog.getPayload() != null) {
            try {
                payload = objectMapper.readValue(txLog.getPayload(), new TypeReference<Map<String, Object>>() {});
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize payload for txId={}: {}", txLog.getTransactionId(), e.getMessage());
            }
        }
        return new TransactionContext(txLog.getTransactionId(), txLog.getStepName(), payload, txLog.getTenantId());
    }
}