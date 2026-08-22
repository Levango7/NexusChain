package org.nexus.gateway.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 三阶段执行模板（P2-F3 事务边界补偿模式重设计）。
 *
 * <p>所有链上副作用操作（退款 / 提现 / 结算）统一通过本模板执行，
 * 确保事务边界与补偿语义一致。三阶段执行模式：</p>
 *
 * <h3>阶段1：落库 PENDING</h3>
 * <p>在数据库记录操作意图（PENDING 状态），包含操作类型、金额、目标地址、幂等键。
 * 此阶段在 {@code @GlobalTransactional} 包裹的数据库事务内执行，
 * 失败时整个全局事务回滚，无任何副作用残留。</p>
 *
 * <h3>阶段2：链上执行</h3>
 * <p>在数据库事务外执行链上操作（广播交易、等待确认）。
 * <strong>关键设计</strong>：此阶段不在 {@code @GlobalTransactional} 范围内，
 * 因为链上交易不可逆，无法通过 Seata undo_log 回滚。
 * 阶段2 失败时仅记录失败结果，由阶段3 将数据库状态更新为 FAILED，
 * 后续由 {@link CompensationService} 根据操作类型决定是否需要补偿。</p>
 *
 * <h3>阶段3：更新 CONFIRMED/FAILED</h3>
 * <p>根据链上执行结果更新数据库状态：
 * <ul>
 *   <li>阶段2 SUCCESS → 更新为 CONFIRMED（COMPLETED / EXECUTED / SETTLED）</li>
 *   <li>阶段2 FAILED → 更新为 FAILED，触发补偿（由 CompensationService 异步处理）</li>
 *   <li>阶段2 PENDING_CONFIRMATION → 保持 PENDING，由 {@code ReconciliationTask}
 *       定时对账确认最终状态</li>
 * </ul>
 * 此阶段在新的 {@code @GlobalTransactional} 包裹的数据库事务内执行。</p>
 *
 * <h3>事务边界示意</h3>
 * <pre>
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ @GlobalTransactional (阶段1)                            │
 *   │   T record = dbPersist.apply(request);  // PENDING 落库 │
 *   └─────────────────────────────────────────────────────────┘
 *   │  ← 事务外：阶段2 链上执行（不可逆，无法回滚）           │
 *   │     OnChainResult result = onChainExecute.apply(record);│
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ @GlobalTransactional (阶段3)                            │
 *   │   dbConfirm.accept(record, result);  // CONFIRMED/FAILED│
 *   └─────────────────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>与 Phase 1 P1-F3 的关系</h3>
 * <p>P1-F3 已调换 PaymentServiceImpl.refund() 的执行顺序（先落库 PENDING → 链上 →
 * 更新 CONFIRMED）。本模板将该模式标准化，扩展到所有链上副作用操作，
 * 并补充补偿与对账机制。原 P1-F3 的事务边界调换语义完全保留。</p>
 *
 * <h3>幂等性</h3>
 * <p>调用方需保证 {@code dbPersist} 内部基于 {@link ExecutionRequest#getIdempotencyKey()}
 * 做幂等检查（数据库唯一约束兜底）。模板本身不重复执行阶段1，
 * 但若阶段2 异常导致阶段3 未执行，{@link CompensationService} 会基于幂等键
 * 查询链上状态后决定是更新 CONFIRMED 还是 FAILED + 补偿。</p>
 *
 * @param <T> 阶段1 落库返回的数据库记录类型（如 Refund / WithdrawalRequestEntity）
 */
@Component
public class ThreePhaseExecutionTemplate {

    private static final Logger log = LoggerFactory.getLogger(ThreePhaseExecutionTemplate.class);

    /**
     * 低5 改进：阶段2（链上执行）超时时间（秒）。
     *
     * <p>阶段2 为链上副作用操作（广播交易 + 等待确认），可能因链节点不可达、
     * 交易池拥堵、RPC 调用挂起等原因无限等待。通过 {@link CompletableFuture#get(long, TimeUnit)}
     * 设置硬超时，超时后标记 FAILED 触发补偿，避免调度线程被长期阻塞。</p>
     *
     * <p>默认 30 秒，通过 {@code nexus.gatewayservice.execution.phase2-timeout-seconds} 配置覆盖。
     * 30 秒覆盖正常链上广播 + 1~2 个区块确认（以太坊 ~12s/块，3 个确认 ~36s，
     * 但阶段2 仅负责广播 + 入池，确认由 ReconciliationTask 异步对账）。</p>
     */
    @Value("${nexus.gatewayservice.execution.phase2-timeout-seconds:30}")
    private long phase2TimeoutSeconds;

    /**
     * 执行三阶段链上副作用操作。
     *
     * <p><strong>事务边界</strong>：本方法本身不标注 {@code @GlobalTransactional}，
     * 由调用方在更外层决定是否开启全局事务（如退款流程需开启全局事务以协调
     * gateway + wallet-service 分支；纯提现流程可仅用本地事务）。
     * 阶段1 和阶段3 通过 {@code @Transactional(REQUIRES_NEW)} 在新本地事务内执行，
     * 确保阶段2 链上调用不在事务内（避免长事务占用连接 + 链上不可逆无法回滚）。</p>
     *
     * @param request         执行请求（操作意图快照）
     * @param dbPersist       阶段1：落库 PENDING，返回持久化后的记录
     * @param onChainExecute  阶段2：链上执行（事务外），返回链上结果
     * @param dbConfirm       阶段3：根据链上结果更新 CONFIRMED/FAILED
     * @param <T>             数据库记录类型
     * @return 阶段1 持久化的数据库记录（阶段3 可能已更新其字段）
     */
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

        // 阶段1：落库 PENDING（新本地事务，确保 PENDING 记录独立提交，
        // 即使阶段2/3 失败也能被 CompensationService 扫描到）
        T record = persistPhase(request, dbPersist);
        log.debug("Phase 1 (dbPersist) completed: idempotencyKey={}", request.getIdempotencyKey());

        // 阶段2：链上执行（事务外，不可逆）
        // 低5 改进：添加超时机制，避免链节点不可达 / RPC 挂起导致无限等待
        OnChainResult result;
        try {
            CompletableFuture<OnChainResult> phase2Future = CompletableFuture.supplyAsync(
                    () -> {
                        OnChainResult r = onChainExecute.apply(record);
                        return r != null ? r : OnChainResult.failure("onChainExecute returned null", false);
                    });
            result = phase2Future.get(phase2TimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            // 超时：链上执行未在阈值内完成，标记 FAILED 触发补偿
            log.error("Phase 2 (onChainExecute) timeout after {}s: idempotencyKey={}, " +
                    "triggering compensation via phase 3",
                    phase2TimeoutSeconds, request.getIdempotencyKey());
            result = OnChainResult.failure(
                    "on-chain execution timeout after " + phase2TimeoutSeconds + "s", false);
        } catch (ExecutionException ee) {
            // supplyAsync 内部异常（onChainExecute 抛出），解包记录
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            log.error("Phase 2 (onChainExecute) exception: idempotencyKey={}, error={}",
                    request.getIdempotencyKey(), cause.getMessage(), cause);
            result = OnChainResult.failure("on-chain execution exception: " + cause.getMessage(), false);
        } catch (InterruptedException ie) {
            // 调度线程被中断（如优雅停机），恢复中断状态并标记 FAILED
            Thread.currentThread().interrupt();
            log.warn("Phase 2 (onChainExecute) interrupted: idempotencyKey={}",
                    request.getIdempotencyKey());
            result = OnChainResult.failure("on-chain execution interrupted", false);
        } catch (RuntimeException e) {
            log.error("Phase 2 (onChainExecute) exception: idempotencyKey={}, error={}",
                    request.getIdempotencyKey(), e.getMessage(), e);
            result = OnChainResult.failure("on-chain execution exception: " + e.getMessage(), false);
        }
        log.info("Phase 2 (onChainExecute) completed: idempotencyKey={}, status={}, txHash={}",
                request.getIdempotencyKey(), result.getStatus(), result.getTxHash());

        // 阶段3：更新 CONFIRMED/FAILED（新本地事务）
        confirmPhase(record, result, dbConfirm);
        log.debug("Phase 3 (dbConfirm) completed: idempotencyKey={}", request.getIdempotencyKey());

        log.info("ThreePhase execute end: type={}, idempotencyKey={}, finalStatus={}",
                request.getOperationType(), request.getIdempotencyKey(), result.getStatus());
        return record;
    }

    /**
     * 阶段1：落库 PENDING。
     *
     * <p>使用 {@code REQUIRES_NEW} 在新事务内执行，确保 PENDING 记录独立提交。
     * 即使后续阶段2/3 失败，PENDING 记录已落库，可被 {@link CompensationService}
     * 扫描超时后处理。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T persistPhase(ExecutionRequest request, Function<ExecutionRequest, T> dbPersist) {
        return dbPersist.apply(request);
    }

    /**
     * 阶段3：更新 CONFIRMED/FAILED。
     *
     * <p>使用 {@code REQUIRES_NEW} 在新事务内执行，与阶段1 事务隔离。
     * 阶段3 失败时（如数据库连接断开），PENDING/链上结果已固化，
     * 由 {@code ReconciliationTask} 定时对账修正。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> void confirmPhase(T record, OnChainResult result, BiConsumer<T, OnChainResult> dbConfirm) {
        dbConfirm.accept(record, result);
    }
}