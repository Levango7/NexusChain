package org.nexus.gateway.split;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.MerchantOwnershipGuard;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * DelayedSplitService 单元测试 — 使用 Mockito mock repositories。
 *
 * <p>覆盖延迟分账计划创建、状态流转（SCHEDULED → READY → EXECUTING → COMPLETED/FAILED）、
 * 定时任务触发、批量执行等场景。</p>
 */
class DelayedSplitServiceTest {

    private DelayedSplitOrderRepository delayedSplitOrderRepository;
    private SplitService splitService;
    private DelayedSplitService delayedSplitService;

    private static final Long MERCHANT_ID = 500L;
    private static final String RECEIVER_ADDR = "0xReceiver1234567890abcdef1234567890abcdef123456";

    @BeforeEach
    void setUp() {
        delayedSplitOrderRepository = mock(DelayedSplitOrderRepository.class);
        splitService = mock(SplitService.class);
        delayedSplitService = new DelayedSplitService(delayedSplitOrderRepository, splitService);
    }

    @Test
    @DisplayName("scheduleDelayedSplits：成功创建延迟分账计划")
    void scheduleDelayedSplitsSuccess() {
        SplitOrder splitOrder = new SplitOrder();
        splitOrder.setOrderId("ORD-001");
        splitOrder.setPaymentId(1001L);
        splitOrder.setMerchantId(MERCHANT_ID);
        splitOrder.setReceiverAddress(RECEIVER_ADDR);
        splitOrder.setAmount(new BigDecimal("100"));
        splitOrder.setSplitType(SplitRule.SplitType.RATIO);
        splitOrder.setSplitValue(new BigDecimal("500"));
        splitOrder.setDescription("分销商佣金");

        when(splitService.calculateSplits("ORD-001", 1001L, MERCHANT_ID, new BigDecimal("1000")))
                .thenReturn(List.of(splitOrder));
        when(delayedSplitOrderRepository.save(any(DelayedSplitOrder.class))).thenAnswer(inv -> {
            DelayedSplitOrder order = inv.getArgument(0);
            order.setId(1L);
            return order;
        });

        List<DelayedSplitOrder> result = delayedSplitService.scheduleDelayedSplits(
                "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("1000"), 3);

        assertEquals(1, result.size());
        DelayedSplitOrder order = result.get(0);
        assertEquals(DelayedSplitOrder.DelayStatus.SCHEDULED, order.getDelayStatus());
        assertEquals(3, order.getDelayDays());
        assertNotNull(order.getScheduledAt());
        assertEquals("ORD-001", order.getOrderId());
        assertEquals(new BigDecimal("100"), order.getAmount());
    }

    @Test
    @DisplayName("scheduleDelayedSplits：无分账规则时返回空列表")
    void scheduleDelayedSplitsNoSplits() {
        when(splitService.calculateSplits(any(), any(), any(), any()))
                .thenReturn(List.of());

        List<DelayedSplitOrder> result = delayedSplitService.scheduleDelayedSplits(
                "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("1000"), 3);

        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("scheduleDelayedSplits：delayDays < 0 — 抛出异常")
    void scheduleDelayedSplitsNegativeDelay() {
        assertThrows(IllegalArgumentException.class, () ->
                delayedSplitService.scheduleDelayedSplits(
                        "ORD-001", 1001L, MERCHANT_ID, new BigDecimal("1000"), -1));
    }

    @Test
    @DisplayName("transitionScheduledToReady：将到期的 SCHEDULED 订单转为 READY")
    void transitionScheduledToReady() {
        DelayedSplitOrder order1 = new DelayedSplitOrder();
        order1.setId(1L);
        order1.setDelayStatus(DelayedSplitOrder.DelayStatus.SCHEDULED);
        order1.setScheduledAt(LocalDateTime.now().minusDays(1));

        DelayedSplitOrder order2 = new DelayedSplitOrder();
        order2.setId(2L);
        order2.setDelayStatus(DelayedSplitOrder.DelayStatus.SCHEDULED);
        order2.setScheduledAt(LocalDateTime.now().minusHours(1));

        when(delayedSplitOrderRepository.findByDelayStatusAndScheduledAtBefore(
                eq(DelayedSplitOrder.DelayStatus.SCHEDULED), any(LocalDateTime.class)))
                .thenReturn(List.of(order1, order2));
        when(delayedSplitOrderRepository.save(any(DelayedSplitOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        int count = delayedSplitService.transitionScheduledToReady();

        assertEquals(2, count);
        assertEquals(DelayedSplitOrder.DelayStatus.READY, order1.getDelayStatus());
        assertEquals(DelayedSplitOrder.DelayStatus.READY, order2.getDelayStatus());
    }

    @Test
    @DisplayName("transitionScheduledToReady：无到期订单时返回 0")
    void transitionScheduledToReadyNone() {
        when(delayedSplitOrderRepository.findByDelayStatusAndScheduledAtBefore(
                eq(DelayedSplitOrder.DelayStatus.SCHEDULED), any(LocalDateTime.class)))
                .thenReturn(List.of());

        int count = delayedSplitService.transitionScheduledToReady();

        assertEquals(0, count);
    }

    @Test
    @DisplayName("executeDelayedSplit：成功执行 READY 状态的延迟分账")
    void executeDelayedSplitSuccess() {
        DelayedSplitOrder order = new DelayedSplitOrder();
        order.setId(1L);
        order.setOrderId("ORD-001");
        order.setDelayStatus(DelayedSplitOrder.DelayStatus.READY);

        when(delayedSplitOrderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(delayedSplitOrderRepository.save(any(DelayedSplitOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        DelayedSplitOrder result = delayedSplitService.executeDelayedSplit(1L);

        assertEquals(DelayedSplitOrder.DelayStatus.COMPLETED, result.getDelayStatus());
        assertNotNull(result.getExecutedAt());
    }

    @Test
    @DisplayName("executeDelayedSplit：订单不存在 — 抛出异常")
    void executeDelayedSplitNotFound() {
        when(delayedSplitOrderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                delayedSplitService.executeDelayedSplit(999L));
    }

    @Test
    @DisplayName("executeDelayedSplit：状态不是 READY — 抛出异常")
    void executeDelayedSplitNotReady() {
        DelayedSplitOrder order = new DelayedSplitOrder();
        order.setId(1L);
        order.setDelayStatus(DelayedSplitOrder.DelayStatus.SCHEDULED);

        when(delayedSplitOrderRepository.findById(1L)).thenReturn(Optional.of(order));

        assertThrows(IllegalArgumentException.class, () ->
                delayedSplitService.executeDelayedSplit(1L));
    }

    @Test
    @DisplayName("executeAllReadyDelayedSplits：批量执行所有 READY 状态订单")
    void executeAllReadyDelayedSplits() {
        DelayedSplitOrder order1 = new DelayedSplitOrder();
        order1.setId(1L);
        order1.setOrderId("ORD-001");
        order1.setDelayStatus(DelayedSplitOrder.DelayStatus.READY);

        DelayedSplitOrder order2 = new DelayedSplitOrder();
        order2.setId(2L);
        order2.setOrderId("ORD-002");
        order2.setDelayStatus(DelayedSplitOrder.DelayStatus.READY);

        when(delayedSplitOrderRepository.findByDelayStatus(DelayedSplitOrder.DelayStatus.READY))
                .thenReturn(List.of(order1, order2));
        when(delayedSplitOrderRepository.findById(1L)).thenReturn(Optional.of(order1));
        when(delayedSplitOrderRepository.findById(2L)).thenReturn(Optional.of(order2));
        when(delayedSplitOrderRepository.save(any(DelayedSplitOrder.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        List<DelayedSplitOrder> results = delayedSplitService.executeAllReadyDelayedSplits();

        assertEquals(2, results.size());
        assertEquals(DelayedSplitOrder.DelayStatus.COMPLETED, results.get(0).getDelayStatus());
        assertEquals(DelayedSplitOrder.DelayStatus.COMPLETED, results.get(1).getDelayStatus());
    }

    @Test
    @DisplayName("getDelayedSplitsByMerchant：按商户查询延迟分账订单")
    void getDelayedSplitsByMerchant() {
        DelayedSplitOrder order = new DelayedSplitOrder();
        order.setId(1L);
        order.setMerchantId(MERCHANT_ID);

        when(delayedSplitOrderRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(order));

        List<DelayedSplitOrder> result = delayedSplitService.getDelayedSplitsByMerchant(MERCHANT_ID);

        assertEquals(1, result.size());
        assertEquals(MERCHANT_ID, result.get(0).getMerchantId());
    }

    @Test
    @DisplayName("getDelayedSplitsByStatus：按状态查询延迟分账订单")
    void getDelayedSplitsByStatus() {
        DelayedSplitOrder order = new DelayedSplitOrder();
        order.setId(1L);
        order.setDelayStatus(DelayedSplitOrder.DelayStatus.SCHEDULED);

        when(delayedSplitOrderRepository.findByDelayStatus(DelayedSplitOrder.DelayStatus.SCHEDULED))
                .thenReturn(List.of(order));

        List<DelayedSplitOrder> result = delayedSplitService.getDelayedSplitsByStatus(
                DelayedSplitOrder.DelayStatus.SCHEDULED);

        assertEquals(1, result.size());
        assertEquals(DelayedSplitOrder.DelayStatus.SCHEDULED, result.get(0).getDelayStatus());
    }
}