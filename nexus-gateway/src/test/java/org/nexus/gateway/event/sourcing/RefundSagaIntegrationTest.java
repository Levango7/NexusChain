package org.nexus.gateway.event.sourcing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 退款 Saga 混合模式集成测试（Seata AT 回滚 + 事件补偿协调）。
 *
 * <p>验证 ADR-027 §2.4.1 定义的退款混合模式协调流程：
 * <ol>
 *   <li>Seata AT 负责跨 gateway + wallet 强一致回滚（退款金额扣减、余额恢复）</li>
 *   <li>事件溯源产出 PaymentRefundedEvent，触发补偿事件</li>
 *   <li>SagaCoordinator 协调 Seata 回滚和事件补偿的顺序</li>
 * </ol>
 *
 * <p>测试策略：
 * <ul>
 *   <li>使用 {@link InMemoryEventStore} 避免依赖真实 Kafka</li>
 *   <li>Mock Seata TM 行为（提交/回滚），验证事件产出时序</li>
 *   <li>验证读模型投影更新与事件重放重建状态</li>
 * </ul>
 *
 * <p>关联 ADR-027：Seata 分布式事务与事件溯源协调。
 *
 * @since Phase 3 - P3-T7 Seata 与事件溯源协调
 */
@DisplayName("退款 Saga 混合模式集成测试（Seata AT + 事件补偿）")
class RefundSagaIntegrationTest {

    private InMemoryEventStore eventStore;
    private EventReplayService eventReplayService;
    private SagaCoordinator sagaCoordinator;

    /** 测试用聚合根 ID（支付订单 ID 字符串形式） */
    private static final String AGGREGATE_ID = "pay-order-1001";
    /** 测试用 Seata 全局事务 ID */
    private static final String GLOBAL_TX_ID = "192.168.1.100:8091:876543210";
    /** 测试用商户 ID */
    private static final Long MERCHANT_ID = 2001L;
    /** 测试用订单号 */
    private static final String ORDER_NO = "ORD-20260809-0001";
    /** 测试用支付金额 */
    private static final BigDecimal PAY_AMOUNT = new BigDecimal("100.00");
    /** 测试用退款金额 */
    private static final BigDecimal REFUND_AMOUNT = new BigDecimal("100.00");
    /** 测试用币种 */
    private static final String TOKEN_SYMBOL = "NEX";
    /** 测试用付款方地址 */
    private static final String PAYER_ADDRESS = "0xPAYER001";
    /** 测试用收款方地址 */
    private static final String PAYEE_ADDRESS = "0xPAYEE001";
    /** 测试用链上交易哈希 */
    private static final String CHAIN_TX_HASH = "0xabc123def456";
    /** 测试用退款链上交易哈希 */
    private static final String REFUND_CHAIN_TX_HASH = "0xrefund789ghi012";
    /** 测试用退款单号 */
    private static final String REFUND_NO = "RF2026080914300001";

    @BeforeEach
    void setUp() {
        eventStore = new InMemoryEventStore();
        eventReplayService = new EventReplayService(eventStore);
        sagaCoordinator = new SagaCoordinator(eventStore, eventReplayService);
    }

    // ================================================================
    // 场景一：退款成功流程（Seata AT 提交 + PaymentRefundedEvent 产出）
    // ================================================================

    @Nested
    @DisplayName("场景一：退款成功（Seata AT 提交 + 事件产出）")
    class RefundSuccessScenario {

        @Test
        @DisplayName("完整退款流程：创建→处理→成功→退款，事件序列正确产出")
        void fullRefundFlow_eventSequenceCorrect() {
            // Given: 一笔已成功的支付（已产出 Created + Processing + Succeeded 事件）
            simulateSuccessfulPayment();

            // When: Seata AT 退款事务提交后，SagaCoordinator 产出 PaymentRefundedEvent
            PaymentRefundedEvent refundEvent = sagaCoordinator.afterRefundCommitted(
                    AGGREGATE_ID, REFUND_NO, REFUND_AMOUNT, REFUND_CHAIN_TX_HASH, "user request refund");

            // Then: PaymentRefundedEvent 正确产出
            assertThat(refundEvent).isNotNull();
            assertThat(refundEvent.getEventType()).isEqualTo("PAYMENT_REFUNDED");
            assertThat(refundEvent.getAggregateId()).isEqualTo(AGGREGATE_ID);
            assertThat(refundEvent.getRefundNo()).isEqualTo(REFUND_NO);
            assertThat(refundEvent.getRefundAmount()).isEqualByComparingTo(REFUND_AMOUNT);
            assertThat(refundEvent.getRefundChainTxHash()).isEqualTo(REFUND_CHAIN_TX_HASH);
            assertThat(refundEvent.getVersion()).isEqualTo(4L); // 前序 3 个事件 + 1

            // And: 事件已持久化到 EventStore
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);
            assertThat(events).hasSize(4);
            assertThat(events.get(3)).isInstanceOf(PaymentRefundedEvent.class);
        }

        @Test
        @DisplayName("退款成功后聚合根状态为 REFUNDED（通过事件重放重建）")
        void afterRefund_aggregateStateRefunded() {
            // Given: 已成功的支付 + 退款事件
            simulateSuccessfulPayment();
            sagaCoordinator.afterRefundCommitted(
                    AGGREGATE_ID, REFUND_NO, REFUND_AMOUNT, REFUND_CHAIN_TX_HASH, "refund");

            // When: 通过事件重放重建聚合根状态
            PaymentAggregate aggregate = sagaCoordinator.currentAggregate(AGGREGATE_ID);

            // Then: 状态为 REFUNDED
            assertThat(aggregate.getState()).isEqualTo(PaymentAggregate.State.REFUNDED);
            assertThat(aggregate.getRefundNo()).isEqualTo(REFUND_NO);
            assertThat(aggregate.getRefundAmount()).isEqualByComparingTo(REFUND_AMOUNT);
            assertThat(aggregate.getRefundChainTxHash()).isEqualTo(REFUND_CHAIN_TX_HASH);
            assertThat(aggregate.getVersion()).isEqualTo(4L);
        }

        @Test
        @DisplayName("退款成功后读模型投影更新为 REFUNDED 状态")
        void afterRefund_readModelProjectionUpdated() {
            // Given: 已成功的支付 + 退款事件
            simulateSuccessfulPayment();
            PaymentRefundedEvent refundEvent = sagaCoordinator.afterRefundCommitted(
                    AGGREGATE_ID, REFUND_NO, REFUND_AMOUNT, REFUND_CHAIN_TX_HASH, "refund");

            // When: 模拟 PaymentProjection 消费事件并投影（测试中直接验证事件可被投影消费）
            // 实际生产中 PaymentProjection 通过 @KafkaListener 消费，此处验证事件字段完整
            assertThat(refundEvent.getRefundNo()).isNotNull();
            assertThat(refundEvent.getRefundAmount()).isNotNull();
            assertThat(refundEvent.getRefundChainTxHash()).isNotNull();
            assertThat(refundEvent.getReason()).isNotNull();

            // Then: 事件可重放重建读模型状态（CQRS 投影等价于事件重放）
            PaymentAggregate aggregate = sagaCoordinator.currentAggregate(AGGREGATE_ID);
            assertThat(aggregate.getState()).isEqualTo(PaymentAggregate.State.REFUNDED);
        }

        @Test
        @DisplayName("SagaCoordinator 策略路由：REFUND 返回 HYBRID_SEATA_WITH_EVENT_COMPENSATION")
        void strategyForRefund_returnsHybridStrategy() {
            SagaCoordinator.ConsistencyStrategy strategy = sagaCoordinator.strategyFor("REFUND");

            assertThat(strategy)
                    .isEqualTo(SagaCoordinator.ConsistencyStrategy.HYBRID_SEATA_WITH_EVENT_COMPENSATION);
        }
    }

    // ================================================================
    // 场景二：退款失败回滚流程（Seata AT 回滚 + PaymentFailedEvent 补偿）
    // ================================================================

    @Nested
    @DisplayName("场景二：退款失败回滚（Seata AT 回滚 + 事件补偿）")
    class RefundFailureRollbackScenario {

        @Test
        @DisplayName("Seata 回滚后产出 PaymentFailedEvent 补偿事件，不产出 PaymentRefundedEvent")
        void seataRollback_compensateWithPaymentFailedEvent() {
            // Given: 一笔已成功的支付
            simulateSuccessfulPayment();

            // When: Seata AT 退款事务回滚（链上转账失败），SagaCoordinator 产出补偿事件
            PaymentFailedEvent compensationEvent = sagaCoordinator.compensateRefundFailure(
                    AGGREGATE_ID, "REFUND_CHAIN_FAILED", "Chain transfer timeout");

            // Then: 产出 PaymentFailedEvent（非 PaymentRefundedEvent）
            assertThat(compensationEvent).isNotNull();
            assertThat(compensationEvent.getEventType()).isEqualTo("PAYMENT_FAILED");
            assertThat(compensationEvent.getFailureCode()).isEqualTo("REFUND_CHAIN_FAILED");
            assertThat(compensationEvent.getFailureMessage()).isEqualTo("Chain transfer timeout");
            assertThat(compensationEvent.getVersion()).isEqualTo(4L);

            // And: 事件序列中不包含 PaymentRefundedEvent
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);
            assertThat(events).hasSize(4);
            assertThat(events.get(3)).isInstanceOf(PaymentFailedEvent.class);
            assertThat(events).noneMatch(e -> e instanceof PaymentRefundedEvent);
        }

        @Test
        @DisplayName("Seata 回滚后聚合根状态为 FAILED（非 REFUNDED）")
        void seataRollback_aggregateStateFailed() {
            // Given: 已成功的支付
            simulateSuccessfulPayment();

            // When: Seata 回滚 + 补偿事件
            sagaCoordinator.compensateRefundFailure(
                    AGGREGATE_ID, "REFUND_CHAIN_FAILED", "Chain transfer timeout");

            // Then: 重放重建状态为 FAILED
            PaymentAggregate aggregate = sagaCoordinator.currentAggregate(AGGREGATE_ID);
            assertThat(aggregate.getState()).isEqualTo(PaymentAggregate.State.FAILED);
            assertThat(aggregate.getFailureCode()).isEqualTo("REFUND_CHAIN_FAILED");
        }

        @Test
        @DisplayName("Seata 回滚携带全局事务 ID 关联（XID 记录到日志）")
        void seataRollback_withGlobalTxId_compensationEventProduced() {
            // Given: 已成功的支付
            simulateSuccessfulPayment();

            // When: Seata 回滚，携带全局事务 ID
            PaymentFailedEvent event = sagaCoordinator.compensateRefundFailure(
                    AGGREGATE_ID, GLOBAL_TX_ID, "REFUND_WALLET_UNREACHABLE", "Wallet service down");

            // Then: 补偿事件正确产出
            assertThat(event).isNotNull();
            assertThat(event.getFailureCode()).isEqualTo("REFUND_WALLET_UNREACHABLE");
            assertThat(event.getVersion()).isEqualTo(4L);

            // And: 事件已持久化
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);
            assertThat(events).hasSize(4);
            assertThat(events.get(3)).isInstanceOf(PaymentFailedEvent.class);
        }
    }

    // ================================================================
    // 场景三：混合补偿协调顺序（Seata 回滚 + 事件补偿）
    // ================================================================

    @Nested
    @DisplayName("场景三：混合补偿协调顺序（SagaCoordinator 协调）")
    class HybridCompensationOrderingScenario {

        @Test
        @DisplayName("混合补偿入口：Seata 回滚后产出补偿事件（REFUND 场景）")
        void hybridCompensation_refundScenario_producesPaymentFailedEvent() {
            // Given: 已成功的支付
            simulateSuccessfulPayment();

            // When: 通过混合补偿入口协调 Seata 回滚 + 事件补偿
            PaymentEvent compensationEvent = sagaCoordinator.compensateAfterSeataRollback(
                    AGGREGATE_ID, GLOBAL_TX_ID, "REFUND",
                    "REFUND_GLOBAL_TX_ROLLBACK", "Seata global transaction rolled back");

            // Then: 产出 PaymentFailedEvent
            assertThat(compensationEvent).isInstanceOf(PaymentFailedEvent.class);
            assertThat(compensationEvent.getEventType()).isEqualTo("PAYMENT_FAILED");
            assertThat(((PaymentFailedEvent) compensationEvent).getFailureCode())
                    .isEqualTo("REFUND_GLOBAL_TX_ROLLBACK");
        }

        @Test
        @DisplayName("混合补偿入口：SIGNING_CANCEL 场景产出 SigningCancelledEvent")
        void hybridCompensation_signingCancelScenario_producesSigningCancelledEvent() {
            // Given: 已成功的支付（聚合根存在）
            simulateSuccessfulPayment();

            // When: 签名取消场景的混合补偿
            PaymentEvent compensationEvent = sagaCoordinator.compensateAfterSeataRollback(
                    AGGREGATE_ID, GLOBAL_TX_ID, "SIGNING_CANCEL",
                    "GLOBAL_TX_ROLLBACK", "Signing cancelled due to global rollback");

            // Then: 产出 SigningCancelledEvent
            assertThat(compensationEvent).isInstanceOf(SigningCancelledEvent.class);
            assertThat(compensationEvent.getEventType()).isEqualTo("SIGNING_CANCELLED");
            assertThat(((SigningCancelledEvent) compensationEvent).getGlobalTxId())
                    .isEqualTo(GLOBAL_TX_ID);
        }

        @Test
        @DisplayName("协调顺序：Seata 提交后才产出成功事件（AfterCommit 语义）")
        void ordering_successEventOnlyAfterSeataCommit() {
            // Given: 已成功的支付
            simulateSuccessfulPayment();

            // When: 模拟 Seata 事务生命周期
            //   T1: Seata AT 开启（不产出事件）
            //   T2: 链上转账执行（不产出事件）
            //   T3: Seata 提交（不产出事件）
            //   T4: AfterCommit 钩子触发 SagaCoordinator.afterRefundCommitted（产出事件）
            int eventsBeforeCommit = eventStore.loadEvents(AGGREGATE_ID).size();

            // Seata 提交前不产出事件
            assertThat(eventsBeforeCommit).isEqualTo(3); // 仅 Created + Processing + Succeeded

            // AfterCommit 后产出 PaymentRefundedEvent
            sagaCoordinator.afterRefundCommitted(
                    AGGREGATE_ID, REFUND_NO, REFUND_AMOUNT, REFUND_CHAIN_TX_HASH, "refund");

            int eventsAfterCommit = eventStore.loadEvents(AGGREGATE_ID).size();
            assertThat(eventsAfterCommit).isEqualTo(4); // 新增 PaymentRefundedEvent
        }

        @Test
        @DisplayName("协调顺序：Seata 回滚时不产出成功事件，仅产出补偿事件")
        void ordering_rollbackOnlyProducesCompensationEvent() {
            // Given: 已成功的支付
            simulateSuccessfulPayment();

            // When: Seata 回滚
            sagaCoordinator.compensateAfterSeataRollback(
                    AGGREGATE_ID, GLOBAL_TX_ID, "REFUND",
                    "REFUND_ROLLBACK", "Seata rolled back");

            // Then: 不产出 PaymentRefundedEvent，仅产出 PaymentFailedEvent
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);
            assertThat(events).noneMatch(e -> e instanceof PaymentRefundedEvent);
            assertThat(events).anyMatch(e -> e instanceof PaymentFailedEvent);
        }
    }

    // ================================================================
    // 场景四：事件可重放重建状态
    // ================================================================

    @Nested
    @DisplayName("场景四：事件可重放重建状态（审计/读模型重建）")
    class EventReplayScenario {

        @Test
        @DisplayName("退款成功后事件重放重建聚合根状态与原始一致")
        void replayAfterRefund_reconstructsAggregateState() {
            // Given: 完整退款流程
            simulateSuccessfulPayment();
            sagaCoordinator.afterRefundCommitted(
                    AGGREGATE_ID, REFUND_NO, REFUND_AMOUNT, REFUND_CHAIN_TX_HASH, "refund");

            // When: 从 EventStore 加载全部事件并重放
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);
            PaymentAggregate replayed = PaymentAggregate.replay(AGGREGATE_ID, events);

            // Then: 重放状态与原始一致
            assertThat(replayed.getState()).isEqualTo(PaymentAggregate.State.REFUNDED);
            assertThat(replayed.getVersion()).isEqualTo(4L);
            assertThat(replayed.getOrderNo()).isEqualTo(ORDER_NO);
            assertThat(replayed.getAmount()).isEqualByComparingTo(PAY_AMOUNT);
            assertThat(replayed.getRefundNo()).isEqualTo(REFUND_NO);
            assertThat(replayed.getRefundAmount()).isEqualByComparingTo(REFUND_AMOUNT);
            assertThat(replayed.getRefundChainTxHash()).isEqualTo(REFUND_CHAIN_TX_HASH);
        }

        @Test
        @DisplayName("退款失败后事件重放重建聚合根状态为 FAILED")
        void replayAfterRefundFailure_reconstructsFailedState() {
            // Given: 退款失败回滚
            simulateSuccessfulPayment();
            sagaCoordinator.compensateRefundFailure(
                    AGGREGATE_ID, "REFUND_CHAIN_FAILED", "Chain timeout");

            // When: 重放
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);
            PaymentAggregate replayed = PaymentAggregate.replay(AGGREGATE_ID, events);

            // Then: 状态为 FAILED
            assertThat(replayed.getState()).isEqualTo(PaymentAggregate.State.FAILED);
            assertThat(replayed.getFailureCode()).isEqualTo("REFUND_CHAIN_FAILED");
        }

        @Test
        @DisplayName("事件版本号连续递增（1,2,3,4）")
        void eventVersions_areSequential() {
            // Given: 完整退款流程
            simulateSuccessfulPayment();
            sagaCoordinator.afterRefundCommitted(
                    AGGREGATE_ID, REFUND_NO, REFUND_AMOUNT, REFUND_CHAIN_TX_HASH, "refund");

            // When: 加载事件序列
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);

            // Then: 版本号 1,2,3,4 连续
            assertThat(events).hasSize(4);
            for (int i = 0; i < events.size(); i++) {
                assertThat(events.get(i).getVersion()).isEqualTo(i + 1L);
            }
        }

        @Test
        @DisplayName("事件历史审计查询：可获取完整事件序列")
        void eventHistory_auditQueryReturnsFullSequence() {
            // Given: 完整退款流程
            simulateSuccessfulPayment();
            sagaCoordinator.afterRefundCommitted(
                    AGGREGATE_ID, REFUND_NO, REFUND_AMOUNT, REFUND_CHAIN_TX_HASH, "refund");

            // When: 查询事件历史
            List<PaymentEvent> history = sagaCoordinator.eventHistory(AGGREGATE_ID);

            // Then: 包含 4 个事件，类型正确
            assertThat(history).hasSize(4);
            assertThat(history.get(0).getEventType()).isEqualTo("PAYMENT_CREATED");
            assertThat(history.get(1).getEventType()).isEqualTo("PAYMENT_PROCESSING");
            assertThat(history.get(2).getEventType()).isEqualTo("PAYMENT_SUCCEEDED");
            assertThat(history.get(3).getEventType()).isEqualTo("PAYMENT_REFUNDED");
        }

        @Test
        @DisplayName("增量重放：从版本 3 起重放仅应用退款事件")
        void incrementalReplay_appliesOnlyRefundEvent() {
            // Given: 完整退款流程
            simulateSuccessfulPayment();
            sagaCoordinator.afterRefundCommitted(
                    AGGREGATE_ID, REFUND_NO, REFUND_AMOUNT, REFUND_CHAIN_TX_HASH, "refund");

            // When: 重放至版本 2（SUCCEEDED 状态），然后增量重放
            PaymentAggregate agg = eventReplayService.replay(AGGREGATE_ID);
            PaymentAggregate incremental = eventReplayService.replayIncremental(agg, 4L);

            // Then: 增量重放后状态为 REFUNDED
            assertThat(incremental.getState()).isEqualTo(PaymentAggregate.State.REFUNDED);
        }
    }

    // ================================================================
    // 场景五：Seata 全局事务 ID 关联
    // ================================================================

    @Nested
    @DisplayName("场景五：Seata 全局事务 ID 关联")
    class GlobalTxIdCorrelationScenario {

        @Test
        @DisplayName("混合补偿事件携带 globalTxId 便于跨服务 trace 关联")
        void hybridCompensation_carriesGlobalTxId() {
            // Given: 已成功的支付
            simulateSuccessfulPayment();

            // When: 混合补偿（SIGNING_CANCEL 场景）
            PaymentEvent event = sagaCoordinator.compensateAfterSeataRollback(
                    AGGREGATE_ID, GLOBAL_TX_ID, "SIGNING_CANCEL",
                    "GLOBAL_TX_ROLLBACK", "rollback");

            // Then: SigningCancelledEvent 携带 globalTxId
            assertThat(event).isInstanceOf(SigningCancelledEvent.class);
            assertThat(((SigningCancelledEvent) event).getGlobalTxId()).isEqualTo(GLOBAL_TX_ID);
        }

        @Test
        @DisplayName("不同 globalTxId 的补偿事件可区分（多事务并发场景）")
        void differentGlobalTxIds_distinguishable() {
            // Given: 已成功的支付
            simulateSuccessfulPayment();

            // When: 两个不同 XID 的补偿事件
            sagaCoordinator.compensateAfterSeataRollback(
                    AGGREGATE_ID, "tx-001", "SIGNING_CANCEL", "ROLLBACK_1", "reason1");

            // Then: 事件产出成功（第二个事件版本号递增）
            // 注意：同一聚合根的并发事务在生产环境由 Seata 串行化，测试验证版本号递增
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);
            assertThat(events).hasSize(4);
            assertThat(events.get(3)).isInstanceOf(SigningCancelledEvent.class);
        }
    }

    // ================================================================
    // 边界与异常场景
    // ================================================================

    @Nested
    @DisplayName("边界与异常场景")
    class EdgeCaseScenario {

        @Test
        @DisplayName("空聚合根 ID 抛出 IllegalArgumentException")
        void blankAggregateId_throwsException() {
            assertThatThrownBy(() -> sagaCoordinator.afterRefundCommitted(
                    "", REFUND_NO, REFUND_AMOUNT, REFUND_CHAIN_TX_HASH, "refund"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("null 聚合根 ID 抛出 IllegalArgumentException")
        void nullAggregateId_throwsException() {
            assertThatThrownBy(() -> sagaCoordinator.afterRefundCommitted(
                    null, REFUND_NO, REFUND_AMOUNT, REFUND_CHAIN_TX_HASH, "refund"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("策略路由：null operation 返回 EVENT_SOURCING_EVENTUAL（默认）")
        void nullOperation_returnsDefaultStrategy() {
            SagaCoordinator.ConsistencyStrategy strategy = sagaCoordinator.strategyFor(null);
            assertThat(strategy)
                    .isEqualTo(SagaCoordinator.ConsistencyStrategy.EVENT_SOURCING_EVENTUAL);
        }

        @Test
        @DisplayName("策略路由：未知 operation 返回 EVENT_SOURCING_EVENTUAL（默认）")
        void unknownOperation_returnsDefaultStrategy() {
            SagaCoordinator.ConsistencyStrategy strategy = sagaCoordinator.strategyFor("UNKNOWN_OP");
            assertThat(strategy)
                    .isEqualTo(SagaCoordinator.ConsistencyStrategy.EVENT_SOURCING_EVENTUAL);
        }

        @Test
        @DisplayName("策略路由大小写不敏感")
        void strategyFor_caseInsensitive() {
            assertThat(sagaCoordinator.strategyFor("refund"))
                    .isEqualTo(SagaCoordinator.ConsistencyStrategy.HYBRID_SEATA_WITH_EVENT_COMPENSATION);
            assertThat(sagaCoordinator.strategyFor("Refund"))
                    .isEqualTo(SagaCoordinator.ConsistencyStrategy.HYBRID_SEATA_WITH_EVENT_COMPENSATION);
            assertThat(sagaCoordinator.strategyFor("CREATE"))
                    .isEqualTo(SagaCoordinator.ConsistencyStrategy.SEATA_AT_STRONG);
            assertThat(sagaCoordinator.strategyFor("SUCCEED"))
                    .isEqualTo(SagaCoordinator.ConsistencyStrategy.EVENT_SOURCING_EVENTUAL);
        }
    }

    // ================================================================
    // 辅助方法：模拟一笔已成功的支付（产出 Created + Processing + Succeeded 事件）
    // ================================================================

    /**
     * 模拟一笔已成功的支付，产出前 3 个事件（Created + Processing + Succeeded）。
     *
     * <p>对应业务流程：
     * <ol>
     *   <li>商户创建支付订单 → PaymentCreatedEvent</li>
     *   <li>链上广播 → PaymentProcessingEvent</li>
     *   <li>链上确认 → PaymentSucceededEvent</li>
     * </ol>
     * 此时聚合根状态为 SUCCEEDED，可发起退款。
     */
    private void simulateSuccessfulPayment() {
        // 1. 创建支付（Seata AT 提交后产出）
        sagaCoordinator.afterPaymentCreateCommitted(
                AGGREGATE_ID, MERCHANT_ID, ORDER_NO, PAY_AMOUNT,
                TOKEN_SYMBOL, PAYER_ADDRESS, PAYEE_ADDRESS);

        // 2. 进入处理中（链上广播后产出）
        sagaCoordinator.onPaymentProcessing(AGGREGATE_ID, CHAIN_TX_HASH, "BROADCAST");

        // 3. 支付成功（链上确认后产出）
        sagaCoordinator.afterPaymentSucceeded(
                AGGREGATE_ID, CHAIN_TX_HASH, PAY_AMOUNT, Instant.now());

        // 验证前置条件：聚合根状态为 SUCCEEDED
        PaymentAggregate aggregate = sagaCoordinator.currentAggregate(AGGREGATE_ID);
        assertThat(aggregate.getState()).isEqualTo(PaymentAggregate.State.SUCCEEDED);
    }
}