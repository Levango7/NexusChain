package org.nexus.gateway.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

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
    @Transactional
    public boolean execute(List<SagaStep> steps, TransactionContext ctx) {
        String txId = ctx.getTransactionId();

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
}