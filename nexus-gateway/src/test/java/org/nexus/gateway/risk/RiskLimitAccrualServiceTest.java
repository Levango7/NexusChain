package org.nexus.gateway.risk;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.model.OrderStateMachine;
import org.nexus.gateway.model.PaymentOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link RiskLimitAccrualService} 单元测试：状态机咽喉钩子的成员集增减、
 * 守恒性、注册/注销、评估读取与接线。
 */
@ExtendWith(MockitoExtension.class)
class RiskLimitAccrualServiceTest {

    private static final Long MERCHANT_ID = 42L;
    private static final BigDecimal AMOUNT = new BigDecimal("100.50");

    @Mock private RiskLimitAccrualRepository repository;

    private RiskLimitAccrualService service;
    private PaymentOrder order;

    @BeforeEach
    void setUp() {
        // 静态钩子隔离：每个测试显式注册/清理，避免跨测试污染
        OrderStateMachine.setHook(null);
        service = new RiskLimitAccrualService(repository);
        order = new PaymentOrder();
        order.setOrderNo("ORD-A3");
        order.setMerchantId(MERCHANT_ID);
        order.setAmount(AMOUNT);
        order.setCreatedAt(LocalDateTime.now());
        order.setStatus(PaymentOrder.OrderStatus.PENDING);
    }

    @AfterEach
    void tearDown() {
        OrderStateMachine.setHook(null);
    }

    // ==================== 成员集判定 ====================

    @Test
    @DisplayName("成员集：仅 PAID+PAYING 计入（SUBMITTED 排除，与 SUM 状态过滤严格一致）")
    void countedMembershipMatchesSumFilter() {
        assertTrue(RiskLimitAccrualService.counted(PaymentOrder.OrderStatus.PAID));
        assertTrue(RiskLimitAccrualService.counted(PaymentOrder.OrderStatus.PAYING));
        assertFalse(RiskLimitAccrualService.counted(PaymentOrder.OrderStatus.SUBMITTED));
        assertFalse(RiskLimitAccrualService.counted(PaymentOrder.OrderStatus.PENDING));
        assertFalse(RiskLimitAccrualService.counted(PaymentOrder.OrderStatus.REFUND_PENDING));
        assertFalse(RiskLimitAccrualService.counted(PaymentOrder.OrderStatus.FAILED));
    }

    // ==================== 增减逻辑（经状态机全链验证） ====================

    @Test
    @DisplayName("进出对称：PENDING→PAYING 记 +；PAYING→PAID 集内移动不重复记；PAID→REFUND_PENDING 记 -")
    void enterMoveLeaveConservation() {
        service.register();
        when(repository.addAmount(any(), any(), any(), any())).thenReturn(1);

        // 进入：PENDING → PAYING（+amount，DAILY+MONTHLY 各一次）
        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PAYING);
        verify(repository, times(2)).addAmount(eq(MERCHANT_ID), any(), any(), eq(AMOUNT));

        // 集内移动：PAYING → PAID（成员集不变 → 零记账）
        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PAID);
        verify(repository, times(2)).addAmount(any(), any(), any(), any());

        // 离开：PAID → REFUND_PENDING（-amount）
        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.REFUND_PENDING);
        verify(repository, times(2)).addAmount(eq(MERCHANT_ID), any(), any(),
                eq(AMOUNT.negate()));
        // 总记账：进 2（日+月）+ 集内移动 0 + 出 2 = 4 次
        verify(repository, times(4)).addAmount(any(), any(), any(), any());
    }

    @Test
    @DisplayName("FAILED→PENDING 重试回路：PAID→FAILED 记 -，重试回 PAID 记 +（可逆守恒）")
    void retryLoopReversible() {
        service.register();
        when(repository.addAmount(any(), any(), any(), any())).thenReturn(1);
        order.setStatus(PaymentOrder.OrderStatus.PAYING);

        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.FAILED);
        verify(repository, times(2)).addAmount(eq(MERCHANT_ID), any(), any(), eq(AMOUNT.negate()));

        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PENDING);
        verify(repository, times(2)).addAmount(any(), any(), any(), any()); // 集外移动，不记

        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PAYING);
        // +AMOUNT 记账仅重试成功这一次（日+月共 2 次）
        verify(repository, times(2)).addAmount(eq(MERCHANT_ID), any(), any(), eq(AMOUNT));
    }

    @Test
    @DisplayName("首插：UPDATE miss → 插入首行；并发唯一约束冲突 → 重试累加")
    void missThenInsertWithRaceRetry() {
        when(repository.addAmount(eq(MERCHANT_ID), eq(RiskLimitAccrual.PeriodType.DAILY),
                any(), eq(AMOUNT))).thenReturn(0);
        when(repository.addAmount(eq(MERCHANT_ID), eq(RiskLimitAccrual.PeriodType.MONTHLY),
                any(), eq(AMOUNT))).thenReturn(1);
        when(repository.save(any())).thenThrow(new RuntimeException("duplicate key uk_rla"));

        // DAILY：miss + save 冲突 → 重试 addAmount 再 miss → 抛出被吞（warn）；
        // MONTHLY：命中。不抛异常到调用方（状态机可用性优先）
        assertDoesNotThrow(() -> service.onTransition(order,
                PaymentOrder.OrderStatus.PENDING, PaymentOrder.OrderStatus.PAYING));

        verify(repository, times(2)).addAmount(eq(MERCHANT_ID),
                eq(RiskLimitAccrual.PeriodType.DAILY), any(), eq(AMOUNT));
        verify(repository, times(1)).save(any());
    }

    // ==================== 注册与读取 ====================

    @Test
    @DisplayName("register/unregister：钩子生命周期，注销后状态变更零记账")
    void hookLifecycle() {
        service.register();
        when(repository.addAmount(any(), any(), any(), any())).thenReturn(1);
        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PAYING);
        verify(repository, times(2)).addAmount(any(), any(), any(), any());

        service.unregister();
        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PAID);
        verify(repository, times(2)).addAmount(any(), any(), any(), any()); // 注销后无新增
    }

    @Test
    @DisplayName("dailyAccrued/monthlyAccrued：O(1) 读，miss 归零")
    void readPaths() {
        RiskLimitAccrual daily = new RiskLimitAccrual();
        daily.setAccruedAmount(new BigDecimal("123.45"));
        when(repository.findByMerchantIdAndPeriodTypeAndPeriodKey(
                eq(MERCHANT_ID), eq(RiskLimitAccrual.PeriodType.DAILY), any(LocalDate.class)))
                .thenReturn(Optional.of(daily));
        when(repository.findByMerchantIdAndPeriodTypeAndPeriodKey(
                eq(MERCHANT_ID), eq(RiskLimitAccrual.PeriodType.MONTHLY), any(LocalDate.class)))
                .thenReturn(Optional.empty());

        assertEquals(new BigDecimal("123.45"), service.dailyAccrued(MERCHANT_ID));
        assertEquals(0, BigDecimal.ZERO.compareTo(service.monthlyAccrued(MERCHANT_ID)));
    }

    @Test
    @DisplayName("评估接线：注入 accrual 后 DefaultPaymentRiskService 走计数器而非 SUM")
    void riskServiceWiredToAccrual() {
        RiskLimitAccrualService accrual = mock(RiskLimitAccrualService.class);
        // 日累计已近上限：本次 5000 必触发 REJECTED（短路后续月度查询）
        when(accrual.dailyAccrued(MERCHANT_ID)).thenReturn(new BigDecimal("999999"));

        org.nexus.settlement.risk.RiskEngine engine = mock(org.nexus.settlement.risk.RiskEngine.class);
        RiskProfileRepository profileRepo = mock(RiskProfileRepository.class);
        RiskProfile profile = new RiskProfile();
        profile.setMerchantId(MERCHANT_ID);
        profile.setBlacklisted(false);
        profile.setPerTxLimit(new BigDecimal("100000"));
        profile.setDailyLimit(new BigDecimal("1000000"));
        when(profileRepo.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(profile));

        org.nexus.gateway.repository.PaymentOrderRepository orderRepo =
                mock(org.nexus.gateway.repository.PaymentOrderRepository.class);
        DefaultPaymentRiskService risk = new DefaultPaymentRiskService(
                profileRepo, engine, orderRepo, accrual, null);

        PaymentRequest req = new PaymentRequest();
        req.setMerchantId(MERCHANT_ID);
        req.setAmount(new BigDecimal("5000"));
        req.setTokenSymbol("NEX");
        assertEquals(RiskDecision.REJECTED, risk.evaluatePayment(req));

        // 计数器被消费；SUM 查询零调用（自然日口径生效）
        verify(accrual).dailyAccrued(MERCHANT_ID);
        verify(orderRepo, never()).sumMerchantAmountSince(any(), any());
    }
}
