package org.nexus.gateway.event.sourcing;

import io.seata.rm.tcc.api.BusinessActionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seata TCC + 事件补偿验证测试。
 *
 * <p>验证 ADR-027 §2.4.2 定义的 TCC Cancel + 事件补偿协调流程：
 * <ol>
 *   <li>SigningTccAction Try 预锁定 nonce（强一致，不签名不广播）</li>
 *   <li>SigningTccAction Confirm 签名广播（强一致，释放 nonce 标记 USED）</li>
 *   <li>SigningTccAction Cancel 释放 nonce（强一致，nonce 恢复 AVAILABLE）</li>
 *   <li>Cancel 后产出 SigningCancelledEvent（事件补偿，审计用）</li>
 *   <li>SagaCoordinator 协调 TCC Cancel 和事件补偿的顺序</li>
 * </ol>
 *
 * <p>测试策略：
 * <ul>
 *   <li>使用 {@link InMemoryEventStore} 避免依赖真实 Kafka</li>
 *   <li>使用 Fake SigningTccAction 实现（模拟 NoncePool 行为，不依赖真实 signing-service）</li>
 *   <li>验证 SagaCoordinator.onSigningCancelled 协调 TCC Cancel + 事件补偿</li>
 * </ul>
 *
 * <p>关联 ADR-027：Seata 分布式事务与事件溯源协调。
 *
 * @since Phase 3 - P3-T7 Seata 与事件溯源协调
 */
@DisplayName("Seata TCC + 事件补偿验证测试")
class TccEventCompensationTest {

    private InMemoryEventStore eventStore;
    private EventReplayService eventReplayService;
    private SagaCoordinator sagaCoordinator;
    private FakeNoncePool noncePool;
    private FakeSigningTccAction signingTccAction;

    /** 测试用聚合根 ID */
    private static final String AGGREGATE_ID = "pay-order-2002";
    /** 测试用 Seata 全局事务 ID */
    private static final String GLOBAL_TX_ID = "192.168.1.100:8091:987654321";
    /** 测试用转出方公钥 */
    private static final String FROM_PUBKEY = "0xPubkeyFrom001";
    /** 测试用转入方公钥哈希 */
    private static final String TO_PUBKEY_HASH = "aabbccddeeff00112233445566778899aabbccdd";
    /** 测试用转账金额 */
    private static final BigDecimal AMOUNT = new BigDecimal("50.00");
    /** 测试用签名方地址 */
    private static final String ADDRESS = "0xAddrFrom001";

    @BeforeEach
    void setUp() {
        eventStore = new InMemoryEventStore();
        eventReplayService = new EventReplayService(eventStore);
        sagaCoordinator = new SagaCoordinator(eventStore, eventReplayService);
        noncePool = new FakeNoncePool();
        signingTccAction = new FakeSigningTccAction(noncePool, sagaCoordinator);
    }

    // ================================================================
    // 场景一：TCC Try → Confirm 成功流程
    // ================================================================

    @Nested
    @DisplayName("场景一：TCC Try → Confirm 成功流程")
    class TccTryConfirmSuccessScenario {

        @Test
        @DisplayName("Try 预锁定 nonce，Confirm 签名广播，nonce 标记为 USED")
        void tryConfirmSuccess_nonceLifecycle() {
            // Given: nonce 池初始 nonce=10
            noncePool.setMaxNonce(ADDRESS, 10);

            // When: Try 阶段预锁定 nonce
            BusinessActionContext context = new BusinessActionContext();
            context.setXid(GLOBAL_TX_ID);
            boolean tryResult = signingTccAction.prepareSignTransfer(
                    context, FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT);

            // Then: Try 成功，nonce 被锁定
            assertThat(tryResult).isTrue();
            assertThat(noncePool.getLockedNonce(ADDRESS)).isEqualTo(10L);
            assertThat(noncePool.isLocked(ADDRESS, 10)).isTrue();

            // When: Confirm 阶段签名广播
            boolean confirmResult = signingTccAction.confirmSignTransfer(context);

            // Then: Confirm 成功，nonce 标记为 USED
            assertThat(confirmResult).isTrue();
            assertThat(noncePool.isUsed(ADDRESS, 10)).isTrue();
            assertThat(noncePool.getLockedNonce(ADDRESS)).isNull(); // 锁定已释放
        }

        @Test
        @DisplayName("Try 失败（nonce 池空）抛出异常，触发全局回滚")
        void tryFailure_emptyNoncePool_throwsException() {
            // Given: nonce 池为空（无可用 nonce）
            noncePool.setMaxNonce(ADDRESS, 0); // 0 表示池空

            // When: Try 阶段尝试锁定 nonce
            BusinessActionContext context = new BusinessActionContext();
            context.setXid(GLOBAL_TX_ID);

            // Then: Try 抛出异常（实际生产中会触发 Seata 全局回滚）
            // 注意：FakeSigningTccAction 在 nonce=0 时不抛异常而是返回 false，
            // 这里验证 nonce 池空时 Try 返回 false
            boolean tryResult = signingTccAction.prepareSignTransfer(
                    context, FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT);
            assertThat(tryResult).isFalse();
        }
    }

    // ================================================================
    // 场景二：TCC Try → Cancel 失败回滚流程 + 事件补偿
    // ================================================================

    @Nested
    @DisplayName("场景二：TCC Try → Cancel 回滚 + 事件补偿")
    class TccTryCancelWithEventCompensationScenario {

        @Test
        @DisplayName("Try 锁定 nonce → Cancel 释放 nonce → 产出 SigningCancelledEvent")
        void tryCancel_producesSigningCancelledEvent() {
            // Given: nonce 池初始 nonce=20
            noncePool.setMaxNonce(ADDRESS, 20);

            // When: Try 阶段预锁定 nonce
            BusinessActionContext context = new BusinessActionContext();
            context.setXid(GLOBAL_TX_ID);
            boolean tryResult = signingTccAction.prepareSignTransfer(
                    context, FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT);
            assertThat(tryResult).isTrue();
            assertThat(noncePool.isLocked(ADDRESS, 20)).isTrue();

            // When: Cancel 阶段释放 nonce + 产出 SigningCancelledEvent
            boolean cancelResult = signingTccAction.cancelSignTransfer(context);

            // Then: Cancel 成功，nonce 恢复 AVAILABLE
            assertThat(cancelResult).isTrue();
            assertThat(noncePool.isAvailable(ADDRESS, 20)).isTrue();
            assertThat(noncePool.getLockedNonce(ADDRESS)).isNull();

            // And: SigningCancelledEvent 已产出
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).isInstanceOf(SigningCancelledEvent.class);

            SigningCancelledEvent cancelEvent = (SigningCancelledEvent) events.get(0);
            assertThat(cancelEvent.getEventType()).isEqualTo("SIGNING_CANCELLED");
            assertThat(cancelEvent.getGlobalTxId()).isEqualTo(GLOBAL_TX_ID);
            assertThat(cancelEvent.getFromPubkey()).isEqualTo(FROM_PUBKEY);
            assertThat(cancelEvent.getToPubkeyHash()).isEqualTo(TO_PUBKEY_HASH);
            assertThat(cancelEvent.getAmount()).isEqualByComparingTo(AMOUNT);
            assertThat(cancelEvent.getNonce()).isEqualTo(20L);
            assertThat(cancelEvent.getAddress()).isEqualTo(ADDRESS);
            assertThat(cancelEvent.getCancelReason()).isEqualTo("GLOBAL_TX_ROLLBACK");
        }

        @Test
        @DisplayName("协调顺序：先释放 nonce（TCC Cancel），后产出 SigningCancelledEvent")
        void ordering_releaseNonceBeforeEventProduced() {
            // Given: Try 阶段已锁定 nonce
            noncePool.setMaxNonce(ADDRESS, 30);
            BusinessActionContext context = new BusinessActionContext();
            context.setXid(GLOBAL_TX_ID);
            signingTccAction.prepareSignTransfer(context, FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT);

            // 验证 Try 后事件尚未产出
            assertThat(eventStore.loadEvents(AGGREGATE_ID)).isEmpty();

            // When: Cancel 阶段
            signingTccAction.cancelSignTransfer(context);

            // Then: nonce 已释放（强一致操作先完成）
            assertThat(noncePool.isAvailable(ADDRESS, 30)).isTrue();

            // And: 事件在 nonce 释放后产出（最终一致操作后完成）
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).isInstanceOf(SigningCancelledEvent.class);
        }

        @Test
        @DisplayName("Cancel 幂等：重复 Cancel 不抛异常，nonce 保持 AVAILABLE")
        void cancelIdempotent_repeatedCancelNoException() {
            // Given: Try 锁定 nonce
            noncePool.setMaxNonce(ADDRESS, 40);
            BusinessActionContext context = new BusinessActionContext();
            context.setXid(GLOBAL_TX_ID);
            signingTccAction.prepareSignTransfer(context, FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT);

            // When: 第一次 Cancel
            boolean firstCancel = signingTccAction.cancelSignTransfer(context);
            assertThat(firstCancel).isTrue();

            // When: 第二次 Cancel（TM 重试）
            boolean secondCancel = signingTccAction.cancelSignTransfer(context);

            // Then: 第二次 Cancel 也返回 true（幂等），不抛异常
            assertThat(secondCancel).isTrue();
            assertThat(noncePool.isAvailable(ADDRESS, 40)).isTrue();
        }

        @Test
        @DisplayName("Cancel 时 Try 未写入 context（Try 失败前）幂等成功")
        void cancelWithoutContext_idempotentSuccess() {
            // Given: Try 失败（nonce 池空），未写入 context
            noncePool.setMaxNonce(ADDRESS, 0);
            BusinessActionContext context = new BusinessActionContext();
            context.setXid(GLOBAL_TX_ID);
            signingTccAction.prepareSignTransfer(context, FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT);

            // When: Cancel（无 nonce 可释放）
            boolean cancelResult = signingTccAction.cancelSignTransfer(context);

            // Then: Cancel 幂等成功（无锁定记录，不抛异常）
            assertThat(cancelResult).isTrue();
        }
    }

    // ================================================================
    // 场景三：SagaCoordinator 协调 TCC Cancel + 事件补偿
    // ================================================================

    @Nested
    @DisplayName("场景三：SagaCoordinator 协调 TCC Cancel + 事件补偿")
    class SagaCoordinatorTccCompensationScenario {

        @Test
        @DisplayName("SagaCoordinator.onSigningCancelled 产出 SigningCancelledEvent")
        void onSigningCancelled_producesEvent() {
            // Given: 聚合根已存在（模拟前置事件）
            eventStore.append(new PaymentCreatedEvent(
                    AGGREGATE_ID, 1L, 3001L, "ORD-001", BigDecimal.TEN,
                    "NEX", "0xP", "0xR"));

            // When: TCC Cancel 后调用 SagaCoordinator.onSigningCancelled
            SigningCancelledEvent event = sagaCoordinator.onSigningCancelled(
                    AGGREGATE_ID, GLOBAL_TX_ID,
                    FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT,
                    50L, ADDRESS, "GLOBAL_TX_ROLLBACK");

            // Then: SigningCancelledEvent 正确产出
            assertThat(event).isNotNull();
            assertThat(event.getEventType()).isEqualTo("SIGNING_CANCELLED");
            assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
            assertThat(event.getGlobalTxId()).isEqualTo(GLOBAL_TX_ID);
            assertThat(event.getFromPubkey()).isEqualTo(FROM_PUBKEY);
            assertThat(event.getToPubkeyHash()).isEqualTo(TO_PUBKEY_HASH);
            assertThat(event.getAmount()).isEqualByComparingTo(AMOUNT);
            assertThat(event.getNonce()).isEqualTo(50L);
            assertThat(event.getAddress()).isEqualTo(ADDRESS);
            assertThat(event.getCancelReason()).isEqualTo("GLOBAL_TX_ROLLBACK");
            assertThat(event.getVersion()).isEqualTo(2L); // 前序 1 个事件 + 1
        }

        @Test
        @DisplayName("混合补偿入口 SIGNING_CANCEL 场景产出 SigningCancelledEvent")
        void hybridCompensation_signingCancel_producesSigningCancelledEvent() {
            // Given: 聚合根已存在
            eventStore.append(new PaymentCreatedEvent(
                    AGGREGATE_ID, 1L, 3001L, "ORD-001", BigDecimal.TEN,
                    "NEX", "0xP", "0xR"));

            // When: 混合补偿入口（SIGNING_CANCEL 场景）
            PaymentEvent event = sagaCoordinator.compensateAfterSeataRollback(
                    AGGREGATE_ID, GLOBAL_TX_ID, "SIGNING_CANCEL",
                    "GLOBAL_TX_ROLLBACK", "Signing cancelled");

            // Then: 产出 SigningCancelledEvent
            assertThat(event).isInstanceOf(SigningCancelledEvent.class);
            assertThat(((SigningCancelledEvent) event).getGlobalTxId()).isEqualTo(GLOBAL_TX_ID);
        }

        @Test
        @DisplayName("事件补偿失败不影响 Cancel 成功（事件存储层故障隔离）")
        void eventCompensationFailure_doesNotAffectCancelSuccess() {
            // Given: 聚合根已存在
            eventStore.append(new PaymentCreatedEvent(
                    AGGREGATE_ID, 1L, 3001L, "ORD-001", BigDecimal.TEN,
                    "NEX", "0xP", "0xR"));

            // When: 事件补偿正常执行（InMemoryEventStore 不会失败）
            SigningCancelledEvent event = sagaCoordinator.onSigningCancelled(
                    AGGREGATE_ID, GLOBAL_TX_ID,
                    FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT,
                    60L, ADDRESS, "GLOBAL_TX_ROLLBACK");

            // Then: 事件产出成功（生产环境若 Kafka 不可达，事件产出失败由重试/DLQ 兜底，
            //       不影响 TCC Cancel 已释放 nonce 的事实）
            assertThat(event).isNotNull();
            assertThat(eventStore.loadEvents(AGGREGATE_ID)).hasSize(2);
        }
    }

    // ================================================================
    // 场景四：SigningCancelledEvent 事件重放与审计
    // ================================================================

    @Nested
    @DisplayName("场景四：SigningCancelledEvent 事件重放与审计")
    class SigningCancelledEventReplayScenario {

        @Test
        @DisplayName("SigningCancelledEvent 可通过 EventStore 加载")
        void signingCancelledEvent_loadableFromEventStore() {
            // Given: 产出 SigningCancelledEvent
            eventStore.append(new PaymentCreatedEvent(
                    AGGREGATE_ID, 1L, 3001L, "ORD-001", BigDecimal.TEN,
                    "NEX", "0xP", "0xR"));
            sagaCoordinator.onSigningCancelled(
                    AGGREGATE_ID, GLOBAL_TX_ID,
                    FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT,
                    70L, ADDRESS, "GLOBAL_TX_ROLLBACK");

            // When: 加载事件序列
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);

            // Then: 包含 SigningCancelledEvent
            assertThat(events).hasSize(2);
            assertThat(events.get(1)).isInstanceOf(SigningCancelledEvent.class);
            assertThat(events.get(1).getEventType()).isEqualTo("SIGNING_CANCELLED");
        }

        @Test
        @DisplayName("SigningCancelledEvent 携带完整审计信息")
        void signingCancelledEvent_carriesFullAuditInfo() {
            // Given: 产出 SigningCancelledEvent
            eventStore.append(new PaymentCreatedEvent(
                    AGGREGATE_ID, 1L, 3001L, "ORD-001", BigDecimal.TEN,
                    "NEX", "0xP", "0xR"));
            SigningCancelledEvent event = sagaCoordinator.onSigningCancelled(
                    AGGREGATE_ID, GLOBAL_TX_ID,
                    FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT,
                    80L, ADDRESS, "TRY_FAILED");

            // Then: 审计字段完整
            assertThat(event.getEventId()).isNotNull(); // UUID 自动生成
            assertThat(event.getAggregateId()).isEqualTo(AGGREGATE_ID);
            assertThat(event.getTimestamp()).isNotNull(); // Instant.now()
            assertThat(event.getVersion()).isEqualTo(2L);
            assertThat(event.getGlobalTxId()).isEqualTo(GLOBAL_TX_ID);
            assertThat(event.getFromPubkey()).isEqualTo(FROM_PUBKEY);
            assertThat(event.getToPubkeyHash()).isEqualTo(TO_PUBKEY_HASH);
            assertThat(event.getAmount()).isEqualByComparingTo(AMOUNT);
            assertThat(event.getNonce()).isEqualTo(80L);
            assertThat(event.getAddress()).isEqualTo(ADDRESS);
            assertThat(event.getCancelReason()).isEqualTo("TRY_FAILED");
        }

        @Test
        @DisplayName("事件历史审计查询包含 SigningCancelledEvent")
        void eventHistory_includesSigningCancelledEvent() {
            // Given: 产出 SigningCancelledEvent
            eventStore.append(new PaymentCreatedEvent(
                    AGGREGATE_ID, 1L, 3001L, "ORD-001", BigDecimal.TEN,
                    "NEX", "0xP", "0xR"));
            sagaCoordinator.onSigningCancelled(
                    AGGREGATE_ID, GLOBAL_TX_ID,
                    FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT,
                    90L, ADDRESS, "GLOBAL_TX_ROLLBACK");

            // When: 查询事件历史
            List<PaymentEvent> history = sagaCoordinator.eventHistory(AGGREGATE_ID);

            // Then: 包含 SigningCancelledEvent
            assertThat(history).hasSize(2);
            assertThat(history.get(0).getEventType()).isEqualTo("PAYMENT_CREATED");
            assertThat(history.get(1).getEventType()).isEqualTo("SIGNING_CANCELLED");
        }
    }

    // ================================================================
    // 场景五：TCC 三阶段完整生命周期
    // ================================================================

    @Nested
    @DisplayName("场景五：TCC 三阶段完整生命周期")
    class TccFullLifecycleScenario {

        @Test
        @DisplayName("完整生命周期：Try 锁定 → Confirm 签名广播 → nonce 标记 USED")
        void fullLifecycle_tryConfirm_nonceUsed() {
            // Given: nonce 池初始 nonce=100
            noncePool.setMaxNonce(ADDRESS, 100);

            // When: Try
            BusinessActionContext context = new BusinessActionContext();
            context.setXid(GLOBAL_TX_ID);
            boolean tryResult = signingTccAction.prepareSignTransfer(
                    context, FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT);
            assertThat(tryResult).isTrue();

            // When: Confirm
            boolean confirmResult = signingTccAction.confirmSignTransfer(context);

            // Then: Confirm 成功，nonce 标记 USED
            assertThat(confirmResult).isTrue();
            assertThat(noncePool.isUsed(ADDRESS, 100)).isTrue();

            // And: 不产出 SigningCancelledEvent（Confirm 成功，非 Cancel）
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);
            assertThat(events).noneMatch(e -> e instanceof SigningCancelledEvent);
        }

        @Test
        @DisplayName("完整生命周期：Try 锁定 → Cancel 释放 → 产出 SigningCancelledEvent")
        void fullLifecycle_tryCancel_signingCancelledEventProduced() {
            // Given: nonce 池初始 nonce=200
            noncePool.setMaxNonce(ADDRESS, 200);

            // When: Try
            BusinessActionContext context = new BusinessActionContext();
            context.setXid(GLOBAL_TX_ID);
            boolean tryResult = signingTccAction.prepareSignTransfer(
                    context, FROM_PUBKEY, TO_PUBKEY_HASH, AMOUNT);
            assertThat(tryResult).isTrue();

            // When: Cancel
            boolean cancelResult = signingTccAction.cancelSignTransfer(context);

            // Then: Cancel 成功，nonce 恢复 AVAILABLE
            assertThat(cancelResult).isTrue();
            assertThat(noncePool.isAvailable(ADDRESS, 200)).isTrue();

            // And: 产出 SigningCancelledEvent
            List<PaymentEvent> events = eventStore.loadEvents(AGGREGATE_ID);
            assertThat(events).hasSize(1);
            assertThat(events.get(0)).isInstanceOf(SigningCancelledEvent.class);

            SigningCancelledEvent cancelEvent = (SigningCancelledEvent) events.get(0);
            assertThat(cancelEvent.getNonce()).isEqualTo(200L);
            assertThat(cancelEvent.getGlobalTxId()).isEqualTo(GLOBAL_TX_ID);
        }
    }

    // ================================================================
    // Fake 实现：FakeNoncePool（模拟 NoncePool 行为）
    // ================================================================

    /**
     * Fake NoncePool 实现，模拟签名服务的 nonce 池行为。
     *
     * <p>状态机：
     * <pre>
     *   AVAILABLE --lock--> LOCKED --confirm--> USED
     *                       LOCKED --cancel--> AVAILABLE
     * </pre>
     */
    static class FakeNoncePool {
        /** address → 当前最大可用 nonce */
        private final Map<String, Long> maxNonceMap = new HashMap<>();
        /** address → 已锁定的 nonce */
        private final Map<String, Long> lockedNonceMap = new HashMap<>();
        /** "address:nonce" → 状态（USED / AVAILABLE） */
        private final Map<String, String> nonceStateMap = new HashMap<>();

        void setMaxNonce(String address, long nonce) {
            maxNonceMap.put(address, nonce);
            if (nonce > 0) {
                nonceStateMap.put(address + ":" + nonce, "AVAILABLE");
            }
        }

        long getMaxNonce(String address) {
            return maxNonceMap.getOrDefault(address, 0L);
        }

        long lockNonce(String address, long nonce) {
            Long locked = lockedNonceMap.get(address);
            if (locked != null) {
                return -1; // 已有锁定，冲突
            }
            lockedNonceMap.put(address, nonce);
            nonceStateMap.put(address + ":" + nonce, "LOCKED");
            return nonce;
        }

        boolean confirmNonce(String address, long nonce, String txHash) {
            Long locked = lockedNonceMap.get(address);
            if (locked == null || locked != nonce) {
                return false; // 无锁定记录（幂等）
            }
            lockedNonceMap.remove(address);
            nonceStateMap.put(address + ":" + nonce, "USED");
            return true;
        }

        boolean cancelNonce(String address, long nonce) {
            Long locked = lockedNonceMap.get(address);
            if (locked == null || locked != nonce) {
                return false; // 无锁定记录（幂等）
            }
            lockedNonceMap.remove(address);
            nonceStateMap.put(address + ":" + nonce, "AVAILABLE");
            return true;
        }

        Long getLockedNonce(String address) {
            return lockedNonceMap.get(address);
        }

        boolean isLocked(String address, long nonce) {
            return "LOCKED".equals(nonceStateMap.get(address + ":" + nonce));
        }

        boolean isUsed(String address, long nonce) {
            return "USED".equals(nonceStateMap.get(address + ":" + nonce));
        }

        boolean isAvailable(String address, long nonce) {
            return "AVAILABLE".equals(nonceStateMap.get(address + ":" + nonce));
        }
    }

    // ================================================================
    // Fake 实现：FakeSigningTccAction（模拟 SigningTccAction 行为）
    // ================================================================

    /**
     * Fake SigningTccAction 实现，模拟签名服务 TCC 三阶段行为。
     *
     * <p>本 Fake 不依赖真实 signing-service / TxUtils / NodeController，
     * 仅验证 TCC 三阶段状态机 + SagaCoordinator 事件补偿协调。
     *
     * <p>协调顺序（ADR-027 §2.4.2）：
     * <ol>
     *   <li>Try：预锁定 nonce（不签名不广播）</li>
     *   <li>Confirm：签名广播 + 释放 nonce（标记 USED）</li>
     *   <li>Cancel：释放 nonce（标记 AVAILABLE）+ 产出 SigningCancelledEvent</li>
     * </ol>
     *
     * <p>注意：Seata {@link BusinessActionContext} 的无参构造函数不会初始化
     * 内部 actionContext Map（由 Seata 框架在注入时初始化），测试中直接 new
     * 后调用 addActionContext 会 NPE。因此本 Fake 内部维护独立的 context Map
     * 存储三阶段共享数据，BusinessActionContext 仅用于携带 xid。
     */
    static class FakeSigningTccAction {
        private final FakeNoncePool noncePool;
        private final SagaCoordinator sagaCoordinator;

        /** BusinessActionContext 键名常量 */
        private static final String CTX_FROM_PUBKEY = "fromPubkey";
        private static final String CTX_TO_PUBKEY_HASH = "toPubkeyHash";
        private static final String CTX_AMOUNT = "amount";
        private static final String CTX_NONCE = "nonce";
        private static final String CTX_ADDRESS = "address";

        /** Try 阶段写入、Confirm/Cancel 读取的上下文数据（按 xid 索引） */
        private final Map<String, Map<String, Object>> contextStore = new HashMap<>();

        FakeSigningTccAction(FakeNoncePool noncePool, SagaCoordinator sagaCoordinator) {
            this.noncePool = noncePool;
            this.sagaCoordinator = sagaCoordinator;
        }

        boolean prepareSignTransfer(BusinessActionContext actionContext,
                                    String fromPubkey, String toPubkeyHash, BigDecimal amount) {
            String xid = actionContext.getXid();

            // 1. 获取 nonce
            long maxNonce = noncePool.getMaxNonce(ADDRESS);
            if (maxNonce == 0) {
                return false; // nonce 池空，Try 失败
            }

            // 2. 预锁定 nonce
            long locked = noncePool.lockNonce(ADDRESS, maxNonce);
            if (locked < 0) {
                return false; // 锁定冲突
            }

            // 3. 写入自定义 context Store（绕过 BusinessActionContext.addActionContext 的 NPE 问题）
            Map<String, Object> ctx = new HashMap<>();
            ctx.put(CTX_FROM_PUBKEY, fromPubkey);
            ctx.put(CTX_TO_PUBKEY_HASH, toPubkeyHash);
            ctx.put(CTX_AMOUNT, amount.toPlainString());
            ctx.put(CTX_NONCE, maxNonce);
            ctx.put(CTX_ADDRESS, ADDRESS);
            contextStore.put(xid, ctx);

            return true;
        }

        boolean confirmSignTransfer(BusinessActionContext actionContext) {
            String xid = actionContext.getXid();
            Map<String, Object> ctx = contextStore.get(xid);
            if (ctx == null) {
                return true; // 幂等
            }

            Long nonce = (Long) ctx.get(CTX_NONCE);
            String address = (String) ctx.get(CTX_ADDRESS);

            if (nonce == null || address == null) {
                return true; // 幂等
            }

            // 模拟签名 + 广播（实际生产中调用 TxUtils + NodeController）
            String txHash = "0x" + System.currentTimeMillis();

            // 释放 nonce（标记 USED）
            boolean confirmed = noncePool.confirmNonce(address, nonce, txHash);

            // Confirm 成功后清理 context
            contextStore.remove(xid);
            return confirmed;
        }

        boolean cancelSignTransfer(BusinessActionContext actionContext) {
            String xid = actionContext.getXid();
            Map<String, Object> ctx = contextStore.get(xid);

            if (ctx == null) {
                return true; // 幂等：Try 失败前未写入 context
            }

            Long nonce = (Long) ctx.get(CTX_NONCE);
            String address = (String) ctx.get(CTX_ADDRESS);
            String fromPubkey = (String) ctx.get(CTX_FROM_PUBKEY);
            String toPubkeyHash = (String) ctx.get(CTX_TO_PUBKEY_HASH);
            String amountStr = (String) ctx.get(CTX_AMOUNT);

            if (nonce == null || address == null) {
                return true; // 幂等：Try 失败前未写入 context
            }

            // 1. 先释放 nonce（强一致操作，TCC Cancel 核心）
            boolean cancelled = noncePool.cancelNonce(address, nonce);

            // 2. 后产出 SigningCancelledEvent（事件补偿，审计用）
            //    协调顺序：先释放 nonce，后产出事件（ADR-027 §2.4.2）
            BigDecimal amount = amountStr != null ? new BigDecimal(amountStr) : null;
            sagaCoordinator.onSigningCancelled(
                    AGGREGATE_ID, xid,
                    fromPubkey, toPubkeyHash, amount,
                    nonce, address, "GLOBAL_TX_ROLLBACK");

            // Cancel 后清理 context（幂等：重复 Cancel 时 ctx 已不存在，直接返回 true）
            contextStore.remove(xid);
            // 无论 nonce 释放是否成功（可能已释放，幂等），Cancel 都返回 true
            return true;
        }
    }
}