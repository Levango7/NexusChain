package org.nexus.gateway.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SAGA 事务管理器 — 编排式补偿事务。
 *
 * <p>中心化编排步骤执行和补偿，而非事件 choreography 方式，便于追踪和恢复。</p>
 *
 * <p>执行流程：</p>
 * <ol>
 *   <li>按顺序执行每个 step，记录 TransactionLog</li>
 *   <li>某步失败 → 反向执行已完成步骤的 compensation</li>
 *   <li>compensation 失败 → 重试（最多 3 次）</li>
 *   <li>所有操作幂等（通过 transactionId 去重）</li>
 * </ol>
 *
 * <p>关键设计决策：</p>
 * <ul>
 *   <li>编排式实现 — 由管理器中心化编排，便于追踪和恢复</li>
 *   <li>补偿幂等 — 每个步骤的 compensate 必须幂等</li>
 *   <li>补偿失败处理 — 标记为 FAILED，由恢复调度器后续重试</li>
 * </ul>
 */
@Service
public class SagaTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(SagaTransactionManager.class);
    private static final int MAX_RETRY = 3;

    private final TransactionLogRepository transactionLogRepository;
    private final ObjectMapper objectMapper;

    /** SagaStep 注册表：以 transactionId 为 key，供恢复调度器查找 steps 执行补偿操作 */
    private final ConcurrentHashMap<String, List<SagaStep>> stepRegistry = new ConcurrentHashMap<>();

    public SagaTransactionManager(TransactionLogRepository transactionLogRepository,
                                   ObjectMapper objectMapper) {
        this.transactionLogRepository = transactionLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行 SAGA 事务。
     *
     * <p>按顺序执行所有步骤，若某步失败则逆向补偿已完成步骤。</p>
     *
     * @param steps SAGA 步骤列表（按执行顺序排列）
     * @param ctx 事务上下文
     * @return {@code true} 所有步骤成功；{@code false} 某步失败且补偿已完成
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean execute(List<SagaStep> steps, TransactionContext ctx) {
        String txId = ctx.getTransactionId();

        // 注册 steps 以供恢复调度器使用（超时事务恢复时通过 transactionId 查找）
        stepRegistry.put(txId, steps);

        // 幂等校验：若该事务已有 SUCCESS 日志，直接返回成功
        List<TransactionLog> existingLogs = transactionLogRepository
                .findByTransactionIdOrderByCreatedAtAsc(txId);
        for (TransactionLog existing : existingLogs) {
            if (existing.getStepStatus() == TransactionStepStatus.SUCCESS) {
                log.info("SAGA transaction already completed: txId={}, skipping", txId);
                return true;
            }
            if (existing.getStepStatus() == TransactionStepStatus.FAILED) {
                log.info("SAGA transaction already failed: txId={}, skipping", txId);
                return false;
            }
        }

        // 记录事务开始
        TransactionLog startLog = createLog(ctx, "start", 0,
                TransactionStepStatus.PENDING, "saga-coordinator");
        transactionLogRepository.save(startLog);

        List<Integer> completedStepIndices = new ArrayList<>();

        // 正向执行各步骤
        for (int i = 0; i < steps.size(); i++) {
            SagaStep step = steps.get(i);
            TransactionContext stepCtx = ctx.forStep(step.getStepName());

            TransactionLog stepLog = createLog(stepCtx, step.getStepName(), i + 1,
                    TransactionStepStatus.PENDING, "saga-coordinator");
            transactionLogRepository.save(stepLog);

            try {
                step.execute(stepCtx);
                stepLog.setStepStatus(TransactionStepStatus.SUCCESS);
                transactionLogRepository.save(stepLog);
                completedStepIndices.add(i);
                log.info("SAGA step {} succeeded: txId={}", step.getStepName(), txId);
            } catch (Exception e) {
                log.warn("SAGA step {} failed: txId={}, error={}",
                        step.getStepName(), txId, e.getMessage());
                stepLog.setStepStatus(TransactionStepStatus.FAILED);
                stepLog.setErrorMessage(e.getMessage());
                transactionLogRepository.save(stepLog);

                // 逆向补偿已完成步骤
                compensateCompletedSteps(steps, ctx, completedStepIndices);

                // 记录事务整体失败
                TransactionLog failedLog = createLog(ctx, "compensated", steps.size() + 1,
                        TransactionStepStatus.FAILED, "saga-coordinator");
                transactionLogRepository.save(failedLog);

                return false;
            }
        }

        // 所有步骤成功，记录事务完成
        TransactionLog successLog = createLog(ctx, "completed", steps.size() + 1,
                TransactionStepStatus.SUCCESS, "saga-coordinator");
        transactionLogRepository.save(successLog);

        // 事务正常完成后从注册表中移除 steps
        stepRegistry.remove(txId);

        log.info("SAGA transaction completed: txId={}", txId);
        return true;
    }

    /**
     * 逆向补偿已完成步骤（含重试逻辑）。
     *
     * @param steps SAGA 步骤列表
     * @param ctx 事务上下文
     * @param completedStepIndices 已完成步骤的索引列表
     */
    private void compensateCompletedSteps(List<SagaStep> steps, TransactionContext ctx,
                                           List<Integer> completedStepIndices) {
        String txId = ctx.getTransactionId();

        // 逆序补偿
        for (int j = completedStepIndices.size() - 1; j >= 0; j--) {
            int stepIdx = completedStepIndices.get(j);
            SagaStep step = steps.get(stepIdx);
            TransactionContext stepCtx = ctx.forStep(step.getStepName());

            TransactionLog compLog = createLog(stepCtx, "compensate-" + step.getStepName(),
                    stepIdx + 1, TransactionStepStatus.COMPENSATE, "saga-coordinator");
            transactionLogRepository.save(compLog);

            boolean compensated = false;
            for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
                try {
                    step.compensate(stepCtx);
                    compLog.setStepStatus(TransactionStepStatus.SUCCESS);
                    transactionLogRepository.save(compLog);
                    compensated = true;
                    log.info("SAGA compensation for step {} succeeded: txId={}",
                            step.getStepName(), txId);
                    break;
                } catch (Exception e) {
                    log.warn("SAGA compensation for step {} failed (attempt {}/{}): txId={}, error={}",
                            step.getStepName(), attempt, MAX_RETRY, txId, e.getMessage());
                    if (attempt == MAX_RETRY) {
                        compLog.setStepStatus(TransactionStepStatus.FAILED);
                        compLog.setErrorMessage(e.getMessage());
                        transactionLogRepository.save(compLog);
                        log.error("SAGA compensation exhausted retries for step {}: txId={}",
                                step.getStepName(), txId);
                    }
                }
            }

            if (!compensated) {
                // 补偿失败，由恢复调度器后续重试
                log.error("SAGA compensation failed for step {}, will be retried by recovery scheduler: txId={}",
                        step.getStepName(), txId);
            }
        }
    }

    /**
     * 创建事务日志记录。
     */
    private TransactionLog createLog(TransactionContext ctx, String stepName,
                                     int stepIndex, TransactionStepStatus status,
                                     String participantId) {
        TransactionLog logEntry = new TransactionLog();
        logEntry.setTransactionId(ctx.getTransactionId());
        logEntry.setTransactionType(TransactionType.SAGA);
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
     * 重试补偿操作（供 TransactionRecoveryScheduler 调用）。
     *
     * <p>当 SAGA 事务的 PENDING 或 COMPENSATE 阶段超时后，恢复调度器调用此方法重试补偿。
     * 从 stepRegistry 中查找原始 SagaStep 列表，重建事务上下文，执行 compensate。</p>
     *
     * <p>对于 PENDING 超时：查找该事务所有已完成步骤（SUCCESS 状态），逆序执行补偿。
     * 对于 COMPENSATE 超时：直接重试对应步骤的补偿操作。</p>
     *
     * @param txLog 超时的事务日志
     * @return {@code true} 恢复成功；{@code false} 恢复失败
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryCompensate(TransactionLog txLog) {
        String txId = txLog.getTransactionId();
        List<SagaStep> steps = stepRegistry.get(txId);
        if (steps == null || steps.isEmpty()) {
            log.warn("SAGA retryCompensate: steps not found in registry (service may have restarted), txId={}", txId);
            txLog.setStepStatus(TransactionStepStatus.FAILED);
            txLog.setErrorMessage("Compensate retry failed: steps not in registry");
            transactionLogRepository.save(txLog);
            return false;
        }

        TransactionContext ctx = rebuildContext(txLog);

        // 根据 txLog 的状态决定补偿策略
        if (txLog.getStepStatus() == TransactionStepStatus.PENDING) {
            // PENDING 超时：该步骤执行中崩溃，需要补偿所有已完成步骤
            return compensateAllCompletedSteps(txLog, steps, ctx);
        } else if (txLog.getStepStatus() == TransactionStepStatus.COMPENSATE) {
            // COMPENSATE 超时：补偿操作执行中崩溃，直接重试该步骤的补偿
            return retrySingleCompensate(txLog, steps, ctx);
        } else {
            log.warn("SAGA retryCompensate: unexpected step status={}, txId={}",
                    txLog.getStepStatus(), txId);
            txLog.setStepStatus(TransactionStepStatus.FAILED);
            txLog.setErrorMessage("Compensate retry failed: unexpected step status");
            transactionLogRepository.save(txLog);
            return false;
        }
    }

    /**
     * 补偿所有已完成步骤（PENDING 超时场景）。
     *
     * <p>查找同一 transactionId 下所有 SUCCESS 状态的步骤日志，逆序执行补偿。</p>
     */
    private boolean compensateAllCompletedSteps(TransactionLog txLog, List<SagaStep> steps,
                                                  TransactionContext ctx) {
        String txId = ctx.getTransactionId();

        // 查询该事务的所有日志，找出已完成的步骤
        List<TransactionLog> allLogs = transactionLogRepository
                .findByTransactionIdOrderByCreatedAtAsc(txId);

        boolean allCompensated = true;
        // 逆序补偿已完成步骤
        for (int i = allLogs.size() - 1; i >= 0; i--) {
            TransactionLog stepLog = allLogs.get(i);
            if (stepLog.getStepStatus() == TransactionStepStatus.SUCCESS
                    && stepLog.getStepIndex() != null && stepLog.getStepIndex() > 0
                    && stepLog.getStepIndex() <= steps.size()) {
                SagaStep step = steps.get(stepLog.getStepIndex() - 1);
                TransactionContext stepCtx = ctx.forStep(step.getStepName());

                try {
                    step.compensate(stepCtx);
                    stepLog.setStepStatus(TransactionStepStatus.SUCCESS);
                    // 补偿成功，记录补偿日志
                    TransactionLog compLog = createLog(stepCtx, "compensate-" + step.getStepName(),
                            stepLog.getStepIndex(), TransactionStepStatus.SUCCESS, "saga-coordinator");
                    transactionLogRepository.save(compLog);
                    log.info("SAGA retryCompensate: step {} compensated, txId={}",
                            step.getStepName(), txId);
                } catch (Exception e) {
                    log.warn("SAGA retryCompensate: step {} compensation failed, txId={}, error={}",
                            step.getStepName(), txId, e.getMessage());
                    TransactionLog compLog = createLog(stepCtx, "compensate-" + step.getStepName(),
                            stepLog.getStepIndex(), TransactionStepStatus.FAILED, "saga-coordinator");
                    compLog.setErrorMessage("Compensate retry failed: " + e.getMessage());
                    transactionLogRepository.save(compLog);
                    allCompensated = false;
                }
            }
        }

        // 更新原始超时日志状态
        if (allCompensated) {
            txLog.setStepStatus(TransactionStepStatus.FAILED);
            txLog.setErrorMessage("SAGA PENDING timeout, all steps compensated");
            transactionLogRepository.save(txLog);

            // 记录事务整体失败（已补偿）
            TransactionLog failedLog = createLog(ctx, "compensated", steps.size() + 1,
                    TransactionStepStatus.FAILED, "saga-coordinator");
            transactionLogRepository.save(failedLog);

            // 恢复成功后从注册表中移除 steps
            stepRegistry.remove(txId);
            log.info("SAGA retryCompensate: all steps compensated, txId={}", txId);
            return true;
        } else {
            txLog.setStepStatus(TransactionStepStatus.FAILED);
            txLog.setErrorMessage("SAGA PENDING timeout, partial compensation failed");
            transactionLogRepository.save(txLog);
            return false;
        }
    }

    /**
     * 重试单个步骤的补偿（COMPENSATE 超时场景）。
     */
    private boolean retrySingleCompensate(TransactionLog txLog, List<SagaStep> steps,
                                            TransactionContext ctx) {
        String txId = ctx.getTransactionId();

        // 从 stepName 中提取原始步骤名称（格式为 "compensate-stepName"）
        String stepName = txLog.getStepName();
        String originalStepName = stepName;
        if (stepName != null && stepName.startsWith("compensate-")) {
            originalStepName = stepName.substring("compensate-".length());
        }

        // 在 steps 列表中查找对应的步骤
        SagaStep targetStep = null;
        for (SagaStep step : steps) {
            if (step.getStepName().equals(originalStepName)) {
                targetStep = step;
                break;
            }
        }

        if (targetStep == null) {
            log.warn("SAGA retryCompensate: step {} not found in steps list, txId={}",
                    originalStepName, txId);
            txLog.setStepStatus(TransactionStepStatus.FAILED);
            txLog.setErrorMessage("Compensate retry failed: step not found");
            transactionLogRepository.save(txLog);
            return false;
        }

        TransactionContext stepCtx = ctx.forStep(targetStep.getStepName());
        try {
            targetStep.compensate(stepCtx);
            txLog.setStepStatus(TransactionStepStatus.SUCCESS);
            transactionLogRepository.save(txLog);
            log.info("SAGA retryCompensate: step {} compensation succeeded, txId={}",
                    targetStep.getStepName(), txId);

            // 检查是否所有补偿都已完成（查询是否还有 COMPENSATE 状态的日志）
            List<TransactionLog> remaining = transactionLogRepository
                    .findByTransactionIdOrderByCreatedAtAsc(txId);
            boolean hasPendingCompensate = false;
            for (TransactionLog l : remaining) {
                if (l.getStepStatus() == TransactionStepStatus.COMPENSATE
                        && !l.getId().equals(txLog.getId())) {
                    hasPendingCompensate = true;
                    break;
                }
            }
            if (!hasPendingCompensate) {
                // 所有补偿已完成，从注册表中移除 steps
                stepRegistry.remove(txId);
            }
            return true;
        } catch (Exception e) {
            log.warn("SAGA retryCompensate: step {} compensation failed, txId={}, error={}",
                    targetStep.getStepName(), txId, e.getMessage());
            txLog.setStepStatus(TransactionStepStatus.FAILED);
            txLog.setErrorMessage("Compensate retry failed: " + e.getMessage());
            transactionLogRepository.save(txLog);
            return false;
        }
    }

    /**
     * 从 TransactionLog 重建 TransactionContext。
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