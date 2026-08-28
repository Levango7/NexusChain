package org.nexus.walletsvc.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 三阶段执行模板（P2-F3，wallet-service 本地副本）。
 *
 * <p>与 {@code org.nexus.gateway.execution.ThreePhaseExecutionTemplate} 语义一致，
 * 因模块隔离在 wallet-service 中保留独立副本。供
 * {@link org.nexus.walletsvc.approval.DefaultWithdrawalApprovalService#executeApprovedWithdrawal}
 * 使用三阶段补偿模式执行提现签名广播。</p>
 *
 * <h3>三阶段执行模式</h3>
 * <ol>
 *   <li>阶段1：落库 PENDING（REQUIRES_NEW 事务）</li>
 *   <li>阶段2：链上执行（事务外，不可逆）</li>
 *   <li>阶段3：更新 CONFIRMED/FAILED（REQUIRES_NEW 事务）</li>
 * </ol>
 */
@Component
public class ThreePhaseExecutionTemplate {

    private static final Logger log = LoggerFactory.getLogger(ThreePhaseExecutionTemplate.class);

    /**
     * 阶段1/阶段3 事务模板（审计修复，与 gateway 副本一致）。
     *
     * <p>早期实现以 {@code @Transactional(REQUIRES_NEW)} 标注 persistPhase/confirmPhase，
     * 但 execute() 以 this 自调用，Spring 代理被绕过，注解不生效。改为编程式
     * {@link TransactionTemplate}（PROPAGATION_REQUIRES_NEW）。</p>
     */
    private final TransactionTemplate phaseTransactionTemplate;

    public ThreePhaseExecutionTemplate(PlatformTransactionManager transactionManager) {
        this.phaseTransactionTemplate = new TransactionTemplate(transactionManager);
        this.phaseTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public <T> T execute(ExecutionRequest request,
                         Function<ExecutionRequest, T> dbPersist,
                         Function<T, OnChainResult> onChainExecute,
                         BiConsumer<T, OnChainResult> dbConfirm) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(dbPersist, "dbPersist");
        Objects.requireNonNull(onChainExecute, "onChainExecute");
        Objects.requireNonNull(dbConfirm, "dbConfirm");

        log.info("ThreePhase execute start: type={}, idempotencyKey={}, businessRefId={}",
                request.getOperationType(), request.getIdempotencyKey(), request.getBusinessRefId());

        T record = persistPhase(request, dbPersist);
        log.debug("Phase 1 (dbPersist) completed: idempotencyKey={}", request.getIdempotencyKey());

        OnChainResult result;
        try {
            result = onChainExecute.apply(record);
            if (result == null) {
                result = OnChainResult.failure("onChainExecute returned null", false);
            }
        } catch (Exception e) {
            log.error("Phase 2 (onChainExecute) exception: idempotencyKey={}, error={}",
                    request.getIdempotencyKey(), e.getMessage(), e);
            result = OnChainResult.failure("on-chain execution exception: " + e.getMessage(), false);
        }
        log.info("Phase 2 (onChainExecute) completed: idempotencyKey={}, status={}, txHash={}",
                request.getIdempotencyKey(), result.getStatus(), result.getTxHash());

        confirmPhase(record, result, dbConfirm);
        log.debug("Phase 3 (dbConfirm) completed: idempotencyKey={}", request.getIdempotencyKey());

        log.info("ThreePhase execute end: type={}, idempotencyKey={}, finalStatus={}",
                request.getOperationType(), request.getIdempotencyKey(), result.getStatus());
        return record;
    }

    /** 阶段1：落库 PENDING（REQUIRES_NEW，独立提交，供 CompensationService 扫描）。 */
    public <T> T persistPhase(ExecutionRequest request, Function<ExecutionRequest, T> dbPersist) {
        return phaseTransactionTemplate.execute(status -> dbPersist.apply(request));
    }

    /** 阶段3：更新 CONFIRMED/FAILED（REQUIRES_NEW，与阶段1 事务隔离）。 */
    public <T> void confirmPhase(T record, OnChainResult result, BiConsumer<T, OnChainResult> dbConfirm) {
        phaseTransactionTemplate.executeWithoutResult(status -> dbConfirm.accept(record, result));
    }
}