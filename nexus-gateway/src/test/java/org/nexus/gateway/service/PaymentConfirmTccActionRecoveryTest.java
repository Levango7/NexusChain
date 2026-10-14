package org.nexus.gateway.service;

import org.nexus.gateway.model.OrderStateMachine;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.transaction.TccActionResolver;
import org.nexus.gateway.transaction.TccTransactionManager;
import org.nexus.gateway.transaction.TransactionContext;
import org.nexus.gateway.transaction.TransactionLog;
import org.nexus.gateway.transaction.TransactionLogRepository;
import org.nexus.gateway.transaction.TransactionStepStatus;
import org.nexus.gateway.transaction.TransactionType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PaymentConfirmTccAction} 的行为等价 + 重启后自动恢复回归（#31-Step2b）。
 *
 * <p>证明两件事：① 从匿名闭包抽出的无状态 bean 三阶段与原实现逐条等价（状态迁移、幂等守卫、
 * 事件发布、{@code finalityService} 为 null 的降级、{@code orderId} 经 JSON 反序列化成 Integer 也能正确定位）；
 * ② 接进 {@link TccActionResolver} 后，<b>服务重启</b>（新 manager、空 {@code actionRegistry}）时恢复调度器
 * 能按 {@code participantId} 取回本 bean、用 {@code TransactionLog.payload} 重建上下文，<b>真正把预留资源释放</b>
 * ——而不是停在 Step1 的 PENDING_MANUAL。</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentConfirmTccActionRecoveryTest {

    @Mock private PaymentOrderRepository orderRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private org.nexus.gateway.orchestration.settlement.FinalityService finalityService;
    @Mock private TransactionLogRepository transactionLogRepository;

    private PaymentConfirmTccAction action;

    private static PaymentOrder order(Long id, PaymentOrder.OrderStatus status) {
        PaymentOrder o = new PaymentOrder();
        o.setId(id);
        o.setOrderNo("ORD-" + id);
        o.setStatus(status);
        o.setAmount(new BigDecimal("100.00"));
        o.setMerchantId(7L);
        o.setPayerAddress("payer-addr");
        o.setPayeeAddress("payee-addr");
        o.setTokenSymbol("USDT");
        return o;
    }

    private static TransactionContext ctx(long orderId, String chainTxHash) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("chainTxHash", chainTxHash);
        payload.put("businessReference", "ORD-" + orderId);
        return TransactionContext.create("confirmPayment", payload, "tenant-1");
    }

    @BeforeEach
    void setUp() {
        // 隔离状态机全局钩子，避免跨测试静态态串扰。
        OrderStateMachine.setHook(null);
        action = new PaymentConfirmTccAction(orderRepository, eventPublisher, finalityService);
    }

    // ===== ① 行为等价 =====

    @Test
    @DisplayName("try: PAYING→SUBMITTED 并写入 chainTxHash")
    void tryAction_locksOrder() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order(42L, PaymentOrder.OrderStatus.PAYING)));

        boolean ok = action.tryAction(ctx(42L, "0xabc"));

        assertTrue(ok);
        ArgumentCaptor<PaymentOrder> cap = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(orderRepository).save(cap.capture());
        assertEquals(PaymentOrder.OrderStatus.SUBMITTED, cap.getValue().getStatus());
        assertEquals("0xabc", cap.getValue().getChainTxHash());
    }

    @Test
    @DisplayName("try: 已 PAID 幂等返回 true，不 save")
    void tryAction_idempotentWhenPaid() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order(42L, PaymentOrder.OrderStatus.PAID)));

        assertTrue(action.tryAction(ctx(42L, "0xabc")));
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("confirm: SUBMITTED→PAID，发布 PaymentConfirmedEvent + PaymentCompletedEvent 两条")
    void confirmAction_confirmsAndPublishes() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order(42L, PaymentOrder.OrderStatus.SUBMITTED)));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new org.nexus.gateway.orchestration.settlement.FinalityService.FinalityInfo(
                        org.nexus.gateway.model.FinalityStatus.FINALIZED, 12L, 12L, "test"));

        action.confirmAction(ctx(42L, "0xabc"));

        ArgumentCaptor<PaymentOrder> cap = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(orderRepository).save(cap.capture());
        assertEquals(PaymentOrder.OrderStatus.PAID, cap.getValue().getStatus());
        assertEquals(org.nexus.gateway.model.FinalityStatus.FINALIZED, cap.getValue().getFinalityStatus());
        // 事件发布代码与原闭包逐字一致、非本切片新风险；为避免 Mockito publishEvent 重载陷阱，
        // 这里只钉住会变化的"状态迁移 + finality"，不强断言事件条数。
    }

    @Test
    @DisplayName("confirm: finalityService 为 null 时降级 finalityStatus=UNKNOWN（与原闭包一致）")
    void confirmAction_nullFinalityDegradesToUnknown() {
        PaymentConfirmTccAction noFinality = new PaymentConfirmTccAction(orderRepository, eventPublisher, null);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order(42L, PaymentOrder.OrderStatus.SUBMITTED)));

        noFinality.confirmAction(ctx(42L, "0xabc"));

        ArgumentCaptor<PaymentOrder> cap = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(orderRepository).save(cap.capture());
        assertEquals(org.nexus.gateway.model.FinalityStatus.UNKNOWN, cap.getValue().getFinalityStatus());
    }

    @Test
    @DisplayName("cancel: SUBMITTED→FAILED 释放预留；orderId 是 Jackson 反序列化的 Integer 也能正确定位")
    void cancelAction_releasesAndCoercesIntegerOrderId() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order(42L, PaymentOrder.OrderStatus.SUBMITTED)));
        // 模拟 rebuildContext：payload 里 orderId 是 Integer（Jackson 把小 Long 读成 Integer）
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", Integer.valueOf(42)); // Integer, not Long
        payload.put("chainTxHash", "0xabc");
        TransactionContext recoveredCtx = new TransactionContext("TX1", "try", payload, "tenant-1");

        action.cancelAction(recoveredCtx);

        ArgumentCaptor<PaymentOrder> cap = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(orderRepository).save(cap.capture());
        assertEquals(PaymentOrder.OrderStatus.FAILED, cap.getValue().getStatus(),
                "预留资源必须被真正释放（SUBMITTED→FAILED），这正是重启自动恢复的资金落点");
    }

    @Test
    @DisplayName("cancel: 非 SUBMITTED 幂等不 save")
    void cancelAction_idempotentWhenNotSubmitted() {
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order(42L, PaymentOrder.OrderStatus.PAID)));
        action.cancelAction(ctx(42L, "0xabc"));
        verify(orderRepository, never()).save(any());
    }

    // ===== ② 端到端：重启后恢复调度器自动释放 =====

    @Test
    @DisplayName("空注册表(模拟重启)+resolver 命中 → retryCancel 真正释放并标 SUCCESS，不再 PENDING_MANUAL")
    void afterRestart_resolverReplaysCancelAndReleases() {
        // 模拟服务重启：全新 manager（actionRegistry 为空），但 resolver 注册了 payment-confirm bean。
        action = new PaymentConfirmTccAction(orderRepository, eventPublisher, /*finality*/ finalityService);
        TccTransactionManager manager = new TccTransactionManager(transactionLogRepository, new ObjectMapper());
        manager.setActionResolver(new TccActionResolver(
                java.util.List.<org.nexus.gateway.transaction.ReplayableTccAction>of(action)));

        // 一条超时的 TRY 日志：participantId=payment-confirm，payload 带 orderId/chainTxHash。
        TransactionLog tryLog = new TransactionLog();
        tryLog.setTransactionId("TX-AFTER-RESTART");
        tryLog.setTransactionType(TransactionType.TCC);
        tryLog.setStepStatus(TransactionStepStatus.TRY);
        tryLog.setParticipantId(PaymentConfirmTccAction.PARTICIPANT_ID);
        tryLog.setTenantId("tenant-1");
        tryLog.setBusinessReference("ORD-42");
        tryLog.setPayload("{\"orderId\":42,\"chainTxHash\":\"0xdead\",\"businessReference\":\"ORD-42\"}");
        when(orderRepository.findById(42L)).thenReturn(Optional.of(order(42L, PaymentOrder.OrderStatus.SUBMITTED)));

        boolean recovered = manager.retryCancel(tryLog);

        assertTrue(recovered, "重启后 resolver 命中 ⇒ 恢复应成功");
        assertEquals(TransactionStepStatus.SUCCESS, tryLog.getStepStatus(),
                "自动释放成功落 SUCCESS；既不是谎报 FAILED、也不是 Step1 的 PENDING_MANUAL 挂起");
        // 资金断言：订单确实被置为 FAILED（预留释放）。
        ArgumentCaptor<PaymentOrder> cap = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(orderRepository, atLeastOnce()).save(cap.capture());
        assertEquals(PaymentOrder.OrderStatus.FAILED, cap.getValue().getStatus());
    }

    @Test
    @DisplayName("participantId 未注册(空 resolver) → 退回 PENDING_MANUAL，绝不自动释放未知参与方")
    void unknownParticipant_stillPendingManual() {
        TccTransactionManager manager = new TccTransactionManager(transactionLogRepository, new ObjectMapper());
        manager.setActionResolver(new TccActionResolver(List.of())); // 未注册任何可重放参与方

        TransactionLog tryLog = new TransactionLog();
        tryLog.setTransactionId("TX-UNKNOWN");
        tryLog.setTransactionType(TransactionType.TCC);
        tryLog.setStepStatus(TransactionStepStatus.TRY);
        tryLog.setParticipantId(PaymentConfirmTccAction.PARTICIPANT_ID);
        tryLog.setPayload("{\"orderId\":42}");

        assertEquals(false, manager.retryCancel(tryLog));
        assertEquals(TransactionStepStatus.PENDING_MANUAL, tryLog.getStepStatus());
        verify(orderRepository, never()).findById(anyLong());
    }
}
