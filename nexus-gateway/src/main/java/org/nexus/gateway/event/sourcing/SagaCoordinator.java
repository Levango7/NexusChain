package org.nexus.gateway.event.sourcing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Saga 协调器：明确何时用 Seata AT 强一致，何时用事件最终一致。
 *
 * <p>NexusChain v2.0 Phase 3 采用"混合一致性"策略，本协调器是策略的显式入口：
 *
 * <h3>一致性边界划分</h3>
 * <table border="1">
 *   <tr><th>场景</th><th>一致性模型</th><th>协调机制</th><th>原因</th></tr>
 *   <tr>
 *     <td>支付创建（扣减余额/锁定资金）</td>
 *     <td>强一致</td>
 *     <td>Seata AT {@code @GlobalTransactional}</td>
 *     <td>余额必须实时准确，超卖/双花不可接受</td>
 *   </tr>
 *   <tr>
 *     <td>支付成功后通知 analytics/webhook</td>
 *     <td>最终一致</td>
 *     <td>事件溯源（Kafka payment-events）</td>
 *     <td>下游分析/通知可容忍秒级延迟，无需阻塞主链路</td>
 *   </tr>
 *   <tr>
 *     <td>退款（链上转账 + 状态回滚）</td>
 *     <td>强一致 + 事件补偿</td>
 *     <td>Seata AT 主事务 + 事件溯源补偿</td>
 *     <td>链上转账必须原子完成；完成后异步通知</td>
 *   </tr>
 * </table>
 *
 * <h3>协调流程</h3>
 * <pre>
 *   1. 支付创建：
 *      Seata AT 开启 → 扣减余额/锁定 → 提交 → 产出 PaymentCreatedEvent → Kafka
 *      （事件产出在 Seata 提交后，避免脏读）
 *
 *   2. 支付成功通知：
 *      链上确认 → 产出 PaymentSucceededEvent → Kafka
 *      （analytics 投影 / webhook 通知异步消费，最终一致）
 *
 *   3. 退款：
 *      Seata AT 开启 → 链上转账 → 更新订单状态 → 提交
 *      → 产出 PaymentRefundedEvent → Kafka
 *      （Seata 回滚则不产出事件；事件补偿通过 Kafka DLQ + 重试）
 * </pre>
 *
 * <p>本类不直接执行 Seata 事务（事务边界由 {@code PaymentServiceImpl} 的
 * {@code @GlobalTransactional} 注解控制），仅负责事件产出的时序协调与策略文档化。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
@Component
public class SagaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(SagaCoordinator.class);

    private final EventStore eventStore;
    private final EventReplayService eventReplayService;

    public SagaCoordinator(EventStore eventStore, EventReplayService eventReplayService) {
        this.eventStore = eventStore;
        this.eventReplayService = eventReplayService;
    }

    // ============ 策略枚举 ============

    /** 一致性策略 */
    public enum ConsistencyStrategy {
        /** Seata AT 强一致（同步扣减/转账，事务边界内） */
        SEATA_AT_STRONG,
        /** 事件溯源最终一致（异步通知/投影，事务边界外） */
        EVENT_SOURCING_EVENTUAL,
        /** 混合：Seata AT 主事务 + 事件补偿（退款场景） */
        HYBRID_SEATA_WITH_EVENT_COMPENSATION
    }

    /**
     * 查询某操作类型应采用的一致性策略。
     *
     * @param operation 操作类型（"CREATE"、"SUCCEED"、"REFUND"、"NOTIFY"）
     * @return 一致性策略
     */
    public ConsistencyStrategy strategyFor(String operation) {
        if (operation == null) {
            return ConsistencyStrategy.EVENT_SOURCING_EVENTUAL;
        }
        return switch (operation.toUpperCase()) {
            case "CREATE" -> ConsistencyStrategy.SEATA_AT_STRONG;
            case "SUCCEED", "NOTIFY" -> ConsistencyStrategy.EVENT_SOURCING_EVENTUAL;
            case "REFUND" -> ConsistencyStrategy.HYBRID_SEATA_WITH_EVENT_COMPENSATION;
            default -> ConsistencyStrategy.EVENT_SOURCING_EVENTUAL;
        };
    }

    // ============ 支付创建后事件产出（Seata 提交后调用） ============

    /**
     * 支付创建 Seata AT 事务提交后，产出 {@link PaymentCreatedEvent}。
     *
     * <p>调用时机：{@code PaymentServiceImpl.initiatePayment} 的
     * {@code @GlobalTransactional} 提交后（AfterCommit 钩子）。
     * 此时余额扣减已持久化，事件产出仅用于异步通知 analytics 投影。
     *
     * @param aggregateId 聚合根 ID（订单 ID 字符串）
     * @param merchantId  商户 ID
     * @param orderNo     订单号
     * @param amount      金额
     * @param tokenSymbol 币种
     * @param payerAddress 付款方
     * @param payeeAddress 收款方
     * @return 产出的事件
     */
    public PaymentCreatedEvent afterPaymentCreateCommitted(
            String aggregateId, Long merchantId, String orderNo, BigDecimal amount,
            String tokenSymbol, String payerAddress, String payeeAddress) {

        ConsistencyStrategy strategy = strategyFor("CREATE");
        log.info("Saga step [CREATE] consistency={}, aggregateId={}, orderNo={}", strategy, aggregateId, orderNo);

        // 重建聚合根以获取当前版本号（事件版本 = 当前版本 + 1）
        PaymentAggregate aggregate = eventReplayService.replay(aggregateId);
        long nextVersion = aggregate.getVersion() + 1;

        PaymentCreatedEvent event = new PaymentCreatedEvent(
                aggregateId, nextVersion, merchantId, orderNo, amount,
                tokenSymbol, payerAddress, payeeAddress);

        eventStore.append(event);
        log.info("PaymentCreatedEvent appended: aggregateId={}, version={}, strategy={}",
                aggregateId, nextVersion, strategy);
        return event;
    }

    // ============ 支付成功后事件产出（最终一致） ============

    /**
     * 支付成功后产出 {@link PaymentSucceededEvent}，触发 analytics 投影 / webhook 通知。
     *
     * <p>本方法在 Seata 事务外调用（链上确认后异步触发），属于最终一致范畴。
     * 失败不影响主链路，事件存储层失败由 Kafka 重试 / DLQ 兜底。
     *
     * @param aggregateId   聚合根 ID
     * @param chainTxHash   链上交易哈希
     * @param settledAmount 结算金额
     * @param paidAt        支付完成时间
     * @return 产出的事件
     */
    public PaymentSucceededEvent afterPaymentSucceeded(
            String aggregateId, String chainTxHash, BigDecimal settledAmount, Instant paidAt) {

        ConsistencyStrategy strategy = strategyFor("SUCCEED");
        log.info("Saga step [SUCCEED] consistency={}, aggregateId={}, chainTxHash={}",
                strategy, aggregateId, chainTxHash);

        PaymentAggregate aggregate = eventReplayService.replay(aggregateId);
        long nextVersion = aggregate.getVersion() + 1;

        PaymentSucceededEvent event = new PaymentSucceededEvent(
                aggregateId, nextVersion, chainTxHash, settledAmount, paidAt);

        eventStore.append(event);
        log.info("PaymentSucceededEvent appended: aggregateId={}, version={}, strategy={}",
                aggregateId, nextVersion, strategy);
        return event;
    }

    /**
     * 支付进入处理中：产出 {@link PaymentProcessingEvent}。
     *
     * @param aggregateId 聚合根 ID
     * @param chainTxHash 链上交易哈希（可空）
     * @param reason      触发原因
     * @return 产出的事件
     */
    public PaymentProcessingEvent onPaymentProcessing(
            String aggregateId, String chainTxHash, String reason) {

        ConsistencyStrategy strategy = strategyFor("NOTIFY");
        log.info("Saga step [PROCESSING] consistency={}, aggregateId={}, reason={}",
                strategy, aggregateId, reason);

        PaymentAggregate aggregate = eventReplayService.replay(aggregateId);
        long nextVersion = aggregate.getVersion() + 1;

        PaymentProcessingEvent event = new PaymentProcessingEvent(
                aggregateId, nextVersion, chainTxHash, reason);

        eventStore.append(event);
        log.info("PaymentProcessingEvent appended: aggregateId={}, version={}", aggregateId, nextVersion);
        return event;
    }

    /**
     * 支付失败：产出 {@link PaymentFailedEvent}。
     *
     * @param aggregateId     聚合根 ID
     * @param failureCode     失败原因码
     * @param failureMessage  失败详情
     * @return 产出的事件
     */
    public PaymentFailedEvent onPaymentFailed(
            String aggregateId, String failureCode, String failureMessage) {

        log.info("Saga step [FAIL] aggregateId={}, code={}", aggregateId, failureCode);

        PaymentAggregate aggregate = eventReplayService.replay(aggregateId);
        long nextVersion = aggregate.getVersion() + 1;

        PaymentFailedEvent event = new PaymentFailedEvent(
                aggregateId, nextVersion, failureCode, failureMessage);

        eventStore.append(event);
        log.info("PaymentFailedEvent appended: aggregateId={}, version={}", aggregateId, nextVersion);
        return event;
    }

    // ============ 退款：Seata AT + 事件补偿 ============

    /**
     * 退款 Seata AT 事务提交后，产出 {@link PaymentRefundedEvent}。
     *
     * <p>调用时机：{@code PaymentServiceImpl.refund} 的
     * {@code @GlobalTransactional} 提交后（AfterCommit 钩子）。
     * 此时链上转账已确认，事件产出用于异步通知 analytics 投影更新。
     *
     * <p>若 Seata AT 回滚（链上转账失败），本方法不应被调用；
     * 调用方需在 AfterCommit 钩子中判断事务状态后再触发。
     *
     * @param aggregateId       聚合根 ID
     * @param refundNo          退款单号
     * @param refundAmount      退款金额
     * @param refundChainTxHash 退款链上交易哈希
     * @param reason            退款原因
     * @return 产出的事件
     */
    public PaymentRefundedEvent afterRefundCommitted(
            String aggregateId, String refundNo, BigDecimal refundAmount,
            String refundChainTxHash, String reason) {

        ConsistencyStrategy strategy = strategyFor("REFUND");
        log.info("Saga step [REFUND] consistency={}, aggregateId={}, refundNo={}",
                strategy, aggregateId, refundNo);

        PaymentAggregate aggregate = eventReplayService.replay(aggregateId);
        long nextVersion = aggregate.getVersion() + 1;

        PaymentRefundedEvent event = new PaymentRefundedEvent(
                aggregateId, nextVersion, refundNo, refundAmount, refundChainTxHash, reason);

        eventStore.append(event);
        log.info("PaymentRefundedEvent appended: aggregateId={}, version={}, strategy={}",
                aggregateId, nextVersion, strategy);
        return event;
    }

    // ============ 事件补偿（退款失败回滚后） ============

    /**
     * 退款 Seata AT 回滚后的事件补偿：产出 {@link PaymentFailedEvent} 标记退款失败。
     *
     * <p>当 Seata AT 退款事务回滚（链上转账失败/钱包不可达）时，
     * 不产出 PaymentRefundedEvent（避免投影误认为退款成功），
     * 而是产出 PaymentFailedEvent 携带退款失败原因，供 analytics 投影记录失败状态。
     *
     * @param aggregateId    聚合根 ID
     * @param failureCode    失败原因码（如 "REFUND_CHAIN_FAILED"）
     * @param failureMessage 失败详情
     * @return 产出的事件
     */
    public PaymentFailedEvent compensateRefundFailure(
            String aggregateId, String failureCode, String failureMessage) {

        log.warn("Saga compensation [REFUND_FAILED] aggregateId={}, code={}, message={}",
                aggregateId, failureCode, failureMessage);

        PaymentAggregate aggregate = eventReplayService.replay(aggregateId);
        long nextVersion = aggregate.getVersion() + 1;

        PaymentFailedEvent event = new PaymentFailedEvent(
                aggregateId, nextVersion, failureCode, failureMessage);

        eventStore.append(event);
        log.warn("Compensation PaymentFailedEvent appended: aggregateId={}, version={}",
                aggregateId, nextVersion);
        return event;
    }

    /**
     * 退款 Seata AT 回滚后的事件补偿（携带全局事务 ID 关联）。
     *
     * <p>重载方法，额外携带 Seata 全局事务 ID（XID），便于跨服务 trace 关联与审计。
     * XID 由 Seata TM 在 {@code @GlobalTransactional} 开启时生成，
     * 通过 RPC header 传播到分支事务，事件产出时记录到日志便于关联。
     *
     * @param aggregateId    聚合根 ID
     * @param globalTxId     Seata 全局事务 ID（XID），用于关联事务与事件
     * @param failureCode    失败原因码
     * @param failureMessage 失败详情
     * @return 产出的事件
     * @since Phase 3 - P3-T7 Seata 与事件溯源协调
     */
    public PaymentFailedEvent compensateRefundFailure(
            String aggregateId, String globalTxId, String failureCode, String failureMessage) {

        log.warn("Saga compensation [REFUND_FAILED] aggregateId={}, globalTxId={}, code={}, message={}",
                aggregateId, globalTxId, failureCode, failureMessage);

        PaymentAggregate aggregate = eventReplayService.replay(aggregateId);
        long nextVersion = aggregate.getVersion() + 1;

        PaymentFailedEvent event = new PaymentFailedEvent(
                aggregateId, nextVersion, failureCode, failureMessage);

        eventStore.append(event);
        log.warn("Compensation PaymentFailedEvent appended: aggregateId={}, version={}, globalTxId={}",
                aggregateId, nextVersion, globalTxId);
        return event;
    }

    // ============ TCC Cancel + 事件补偿（签名取消） ============

    /**
     * TCC Cancel 阶段的事件补偿：产出 {@link SigningCancelledEvent} 记录签名取消事实。
     *
     * <p>当 SigningTccAction Cancel 阶段执行（Seata 全局事务回滚触发）时，
     * nonce 已通过 {@code NoncePool.cancelNonce} 释放回 AVAILABLE，
     * 本方法产出 SigningCancelledEvent 供审计/监控消费方记录"签名已取消，nonce 已释放"。
     *
     * <p>协调顺序（ADR-027 §2.4.2）：
     * <ol>
     *   <li>Cancel 阶段先释放 nonce（强一致，TCC 框架保证幂等）</li>
     *   <li>释放成功后调用本方法产出事件（最终一致，审计用）</li>
     * </ol>
     *
     * <p>本方法不参与业务决策，事件产出失败不影响 Cancel 成功。
     * 调用方应在 nonce 释放成功后调用本方法，忽略事件产出异常。
     *
     * @param aggregateId   聚合根 ID（关联的支付订单 ID）
     * @param globalTxId    Seata 全局事务 ID（XID）
     * @param fromPubkey    转出方公钥（Try 阶段写入）
     * @param toPubkeyHash  转入方公钥哈希（Try 阶段写入）
     * @param amount        转账金额（Try 阶段写入）
     * @param nonce         被释放的 nonce
     * @param address       签名方地址
     * @param cancelReason  Cancel 原因（如 "GLOBAL_TX_ROLLBACK"）
     * @return 产出的 SigningCancelledEvent
     * @since Phase 3 - P3-T7 Seata 与事件溯源协调
     */
    public SigningCancelledEvent onSigningCancelled(
            String aggregateId, String globalTxId,
            String fromPubkey, String toPubkeyHash, BigDecimal amount,
            Long nonce, String address, String cancelReason) {

        ConsistencyStrategy strategy = strategyFor("REFUND");
        log.warn("Saga compensation [SIGNING_CANCELLED] consistency={}, aggregateId={}, globalTxId={}, "
                        + "address={}, nonce={}, reason={}",
                strategy, aggregateId, globalTxId, address, nonce, cancelReason);

        PaymentAggregate aggregate = eventReplayService.replay(aggregateId);
        long nextVersion = aggregate.getVersion() + 1;

        SigningCancelledEvent event = new SigningCancelledEvent(
                aggregateId, nextVersion, globalTxId,
                fromPubkey, toPubkeyHash, amount, nonce, address, cancelReason);

        eventStore.append(event);
        log.warn("SigningCancelledEvent appended: aggregateId={}, version={}, globalTxId={}, nonce={}",
                aggregateId, nextVersion, globalTxId, nonce);
        return event;
    }

    // ============ 混合补偿动作（Seata 回滚 + 事件补偿） ============

    /**
     * 混合补偿动作：Seata 全局事务回滚 + 事件补偿协调入口。
     *
     * <p>当 Seata 全局事务回滚时，本方法协调两类补偿：
     * <ol>
     *   <li><b>Seata 回滚</b>：由 Seata 框架自动执行（AT 自动回滚 undo_log，TCC 调用 Cancel），
     *       本方法不直接触发，仅记录回滚事实。</li>
     *   <li><b>事件补偿</b>：产出补偿事件（PaymentFailedEvent / SigningCancelledEvent），
     *       供投影更新为失败状态，避免投影误认为操作成功。</li>
     * </ol>
     *
     * <p>调用时机：Seata 全局事务回滚完成后（AfterCompletion 钩子，rollbackOnly 状态）。
     * 调用方需在 AfterCompletion 钩子中判断事务状态后再触发本方法。
     *
     * <p>协调规则（ADR-027 §2.4.1）：
     * <ul>
     *   <li>Seata 回滚成功后才产出补偿事件（避免事件先到但事务未回滚的虚假状态）</li>
     *   <li>补偿事件不参与 Seata 事务（事件产出失败不影响回滚成功）</li>
     *   <li>不产出"成功类"事件（如 PaymentRefundedEvent），仅产出"失败/取消类"事件</li>
     * </ul>
     *
     * @param aggregateId    聚合根 ID
     * @param globalTxId     Seata 全局事务 ID（XID）
     * @param scenario       补偿场景（"REFUND" / "SIGNING_CANCEL" / "SWEEP" / "WITHDRAWAL"）
     * @param failureCode    失败原因码
     * @param failureMessage 失败详情
     * @return 产出的补偿事件
     * @since Phase 3 - P3-T7 Seata 与事件溯源协调
     */
    public PaymentEvent compensateAfterSeataRollback(
            String aggregateId, String globalTxId, String scenario,
            String failureCode, String failureMessage) {

        log.warn("Saga hybrid compensation [{}] aggregateId={}, globalTxId={}, code={}, message={}",
                scenario, aggregateId, globalTxId, failureCode, failureMessage);

        PaymentAggregate aggregate = eventReplayService.replay(aggregateId);
        long nextVersion = aggregate.getVersion() + 1;

        PaymentEvent compensationEvent;
        if ("SIGNING_CANCEL".equalsIgnoreCase(scenario)) {
            // 签名取消场景：产出 SigningCancelledEvent
            compensationEvent = new SigningCancelledEvent(
                    aggregateId, nextVersion, globalTxId,
                    null, null, null, null, null, failureCode);
        } else {
            // 默认：产出 PaymentFailedEvent 标记业务失败
            compensationEvent = new PaymentFailedEvent(
                    aggregateId, nextVersion, failureCode, failureMessage);
        }

        eventStore.append(compensationEvent);
        log.warn("Hybrid compensation event appended: scenario={}, aggregateId={}, version={}, globalTxId={}, eventType={}",
                scenario, aggregateId, nextVersion, globalTxId, compensationEvent.getEventType());
        return compensationEvent;
    }

    // ============ 聚合根状态查询 ============

    /**
     * 查询聚合根当前状态（通过事件重放）。
     *
     * @param aggregateId 聚合根 ID
     * @return 聚合根当前状态
     */
    public PaymentAggregate currentAggregate(String aggregateId) {
        return eventReplayService.replay(aggregateId);
    }

    /**
     * 查询聚合根历史事件序列（审计用）。
     *
     * @param aggregateId 聚合根 ID
     * @return 事件序列
     */
    public List<PaymentEvent> eventHistory(String aggregateId) {
        return eventStore.loadEvents(aggregateId);
    }
}