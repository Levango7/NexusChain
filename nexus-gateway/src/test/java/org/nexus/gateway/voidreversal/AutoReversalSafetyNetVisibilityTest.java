package org.nexus.gateway.voidreversal;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 自动冲正"安全网"可见性测试（任务 #23，语义裁定：选最稳 —— 只让缺陷可见，不改资金语义）。
 *
 * <p>钉住两件结构性事实：</p>
 * <ol>
 *   <li><b>互斥</b>：调度器扫描 {@code status = PAYING}，而冲正前置条件要求 {@code PAID}
 *       ⇒ 每笔候选必然被 {@code ORDER_NOT_PAID} 拒绝，本调度器至今未提供任何保护。</li>
 *   <li><b>可见</b>：这类"预期内的结构性拒绝"必须聚合成一条带稳定标记的 WARN，
 *       而不是刷 3 副本 × 每 60 秒的 ERROR 噪声。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class AutoReversalSafetyNetVisibilityTest {

    /** 告警标记的线上契约（字面量而非引用常量：这样"只有测试"的提交也能编译，红点落在运行期断言而不是编译错误）。 */
    private static final String MARKER = "AUTO_REVERSAL_SAFETY_NET_INERT";

    @Mock private PaymentOrderRepository paymentOrderRepository;
    @Mock private ReversalRequestRepository reversalRequestRepository;

    private ReversalService realReversalService;
    private AutoReversalScheduler scheduler;

    private Logger schedulerLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        // 用真实的 ReversalService 来钉住"冲正前置条件"这一事实，而不是把它 mock 掉。
        realReversalService = new ReversalService(reversalRequestRepository, paymentOrderRepository,
                mock(AccountService.class), mock(ApplicationEventPublisher.class));
        scheduler = new AutoReversalScheduler(paymentOrderRepository, realReversalService,
                reversalRequestRepository);

        schedulerLogger = (Logger) LoggerFactory.getLogger(AutoReversalScheduler.class);
        originalLevel = schedulerLogger.getLevel();
        schedulerLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        schedulerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        schedulerLogger.detachAppender(appender);
        schedulerLogger.setLevel(originalLevel);
    }

    // ==================== 结构性互斥 ====================

    @Test
    @DisplayName("冲正前置条件：PAYING 订单必然被 ORDER_NOT_PAID 拒绝")
    void reversalPreconditionRejectsPayingOrder() {
        PaymentOrder paying = order(1L, PaymentOrder.OrderStatus.PAYING);
        when(paymentOrderRepository.findById(1L)).thenReturn(java.util.Optional.of(paying));

        VoidReversalException ex = assertThrows(VoidReversalException.class,
                () -> realReversalService.autoReversal(1L, "reason"));

        assertEquals("ORDER_NOT_PAID", ex.getErrorCode());
    }

    @Test
    @DisplayName("扫描判据是 PAYING（与上面的前置条件互斥）")
    void scanPredicateIsPaying() {
        when(paymentOrderRepository.findByStatusAndCreatedAtBefore(
                eq(PaymentOrder.OrderStatus.PAYING), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        scheduler.scanTimeoutOrders();

        verify(paymentOrderRepository).findByStatusAndCreatedAtBefore(
                eq(PaymentOrder.OrderStatus.PAYING), any(LocalDateTime.class));
    }

    // ==================== 可见性 ====================

    @Test
    @DisplayName("安全网空转必须浮出水面：不抛异常、不落库、输出结构化告警")
    void schedulerSurfacesInertSafetyNetWithoutPropagating() {
        PaymentOrder paying = order(1L, PaymentOrder.OrderStatus.PAYING);
        when(paymentOrderRepository.findByStatusAndCreatedAtBefore(
                eq(PaymentOrder.OrderStatus.PAYING), any(LocalDateTime.class)))
                .thenReturn(List.of(paying));
        when(paymentOrderRepository.findById(1L)).thenReturn(java.util.Optional.of(paying));
        when(reversalRequestRepository.findByOrderId(1L)).thenReturn(Collections.emptyList());

        // 真实服务对 PAYING 必然拒绝；这里不吞异常，验证调度器守住了"不让它逃逸"。
        assertDoesNotThrow(() -> scheduler.scanTimeoutOrders());

        verify(reversalRequestRepository, never()).save(any());
        assertTrue(hasWarningWithMarker(),
                "必须输出带 " + MARKER + " 标记的 WARN");
    }

    @Test
    @DisplayName("N 笔结构性拒绝聚合成 1 条告警（不刷噪声）")
    void preconditionRejectionsAreAggregatedIntoOneAlarm() {
        PaymentOrder o1 = order(1L, PaymentOrder.OrderStatus.PAYING);
        PaymentOrder o2 = order(2L, PaymentOrder.OrderStatus.PAYING);
        PaymentOrder o3 = order(3L, PaymentOrder.OrderStatus.PAYING);
        when(paymentOrderRepository.findByStatusAndCreatedAtBefore(
                eq(PaymentOrder.OrderStatus.PAYING), any(LocalDateTime.class)))
                .thenReturn(List.of(o1, o2, o3));
        when(paymentOrderRepository.findById(1L)).thenReturn(java.util.Optional.of(o1));
        when(paymentOrderRepository.findById(2L)).thenReturn(java.util.Optional.of(o2));
        when(paymentOrderRepository.findById(3L)).thenReturn(java.util.Optional.of(o3));
        when(reversalRequestRepository.findByOrderId(1L)).thenReturn(Collections.emptyList());
        when(reversalRequestRepository.findByOrderId(2L)).thenReturn(Collections.emptyList());
        when(reversalRequestRepository.findByOrderId(3L)).thenReturn(Collections.emptyList());

        scheduler.scanTimeoutOrders();

        long alarms = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(MARKER))
                .count();
        assertEquals(1, alarms, "3 笔被拒只应产出 1 条聚合告警");
    }

    @Test
    @DisplayName("非结构性的真异常仍按 ERROR 逐笔记录（不被降级成噪声）")
    void unexpectedFailureStillLoggedAsError() {
        PaymentOrder paying = order(1L, PaymentOrder.OrderStatus.PAYING);
        when(paymentOrderRepository.findByStatusAndCreatedAtBefore(
                eq(PaymentOrder.OrderStatus.PAYING), any(LocalDateTime.class)))
                .thenReturn(List.of(paying));
        when(reversalRequestRepository.findByOrderId(1L)).thenReturn(Collections.emptyList());

        ReversalService exploding = mock(ReversalService.class);
        when(exploding.autoReversal(eq(1L), anyString()))
                .thenThrow(new IllegalStateException("db down"));
        AutoReversalScheduler s = new AutoReversalScheduler(
                paymentOrderRepository, exploding, reversalRequestRepository);

        assertDoesNotThrow(s::scanTimeoutOrders);

        assertTrue(hasEvent(Level.ERROR), "真异常必须留 ERROR");
        assertFalse(hasWarningWithMarker(), "真异常不应偷用安全网标记");
    }

    // ==================== 辅助 ====================

    private boolean hasWarningWithMarker() {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getFormattedMessage().contains(MARKER));
    }

    private boolean hasEvent(Level level) {
        return appender.list.stream().anyMatch(e -> e.getLevel() == level);
    }

    private PaymentOrder order(Long id, PaymentOrder.OrderStatus status) {
        PaymentOrder o = new PaymentOrder();
        o.setId(id);
        o.setOrderNo("NEX-" + id);
        o.setMerchantId(100L);
        o.setAmount(new BigDecimal("1000"));
        o.setStatus(status);
        o.setCreatedAt(LocalDateTime.now().minusHours(1));
        return o;
    }
}
