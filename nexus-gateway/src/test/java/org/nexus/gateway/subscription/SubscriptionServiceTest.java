package org.nexus.gateway.subscription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultSubscriptionService} 单元测试（P4-T8）。
 *
 * <p>覆盖订阅全生命周期：创建（含/不含试用期）、取消、升级（按比例即时扣款）、
 * 降级（下个周期生效）、周期扣款成功/失败、试用期转正、dunning 集成。</p>
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionServiceTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionPlanRepository planRepository;
    @Mock private ChargeExecutor chargeExecutor;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DunningManager dunningManager;
    private ProrationCalculator prorationCalculator;
    private DefaultSubscriptionService service;

    @BeforeEach
    void setUp() {
        SubscriptionProperties properties = new SubscriptionProperties();
        dunningManager = new DunningManager(eventPublisher, properties);
        prorationCalculator = new ProrationCalculator();
        service = new DefaultSubscriptionService(subscriptionRepository, planRepository,
                chargeExecutor, dunningManager, prorationCalculator);
    }

    private SubscriptionPlan plan(String planId, BigDecimal amount, BillingPeriod period, int trialDays) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setPlanId(planId);
        p.setName("Plan " + planId);
        p.setAmount(amount);
        p.setBillingPeriod(period);
        p.setTrialPeriodDays(trialDays);
        p.setCurrency("NEX");
        p.setEnabled(true);
        return p;
    }

    private SubscriptionPlan plan(String planId, BigDecimal amount) {
        return plan(planId, amount, BillingPeriod.MONTHLY, 0);
    }

    @Test
    @DisplayName("createSubscription: 无试用期 -> ACTIVE，nextChargeAt=periodEnd")
    void createSubscription_noTrial_active() {
        when(planRepository.findByPlanId("plan-basic")).thenReturn(
                Optional.of(plan("plan-basic", new BigDecimal("1000"))));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = service.createSubscription(100L, "cust-1", "0xPayer", "0xPayee", "plan-basic");

        assertEquals(SubscriptionStatus.ACTIVE, result.getStatus());
        assertNull(result.getTrialEnd());
        assertEquals(100L, result.getMerchantId());
        assertEquals("cust-1", result.getCustomerId());
        assertEquals("plan-basic", result.getPlanId());
        assertNotNull(result.getSubscriptionId());
        assertNotNull(result.getCurrentPeriodStart());
        assertNotNull(result.getCurrentPeriodEnd());
        assertEquals(result.getCurrentPeriodEnd(), result.getNextChargeAt());
        assertEquals(0, result.getDunningCount());
        assertEquals(0, result.getChargedCount());
    }

    @Test
    @DisplayName("createSubscription: 有试用期 -> TRIAL，trialEnd=now+trialDays")
    void createSubscription_withTrial_trialStatus() {
        when(planRepository.findByPlanId("plan-trial")).thenReturn(
                Optional.of(plan("plan-trial", new BigDecimal("1000"), BillingPeriod.MONTHLY, 14)));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        LocalDateTime before = LocalDateTime.now();
        Subscription result = service.createSubscription(100L, "cust-1", "0xPayer", "0xPayee", "plan-trial");

        assertEquals(SubscriptionStatus.TRIAL, result.getStatus());
        assertNotNull(result.getTrialEnd());
        // trialEnd ≈ now + 14 days
        assertTrue(result.getTrialEnd().isAfter(before.plusDays(14).minusMinutes(1)));
        assertTrue(result.getTrialEnd().isBefore(before.plusDays(14).plusMinutes(1)));
        // nextChargeAt = trialEnd
        assertEquals(result.getTrialEnd(), result.getNextChargeAt());
    }

    @Test
    @DisplayName("createSubscription: 计划不存在 -> 抛异常")
    void createSubscription_planNotFound_throws() {
        when(planRepository.findByPlanId("ghost")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () ->
                service.createSubscription(100L, "cust-1", "0xPayer", "0xPayee", "ghost"));
    }

    @Test
    @DisplayName("createSubscription: 计划已禁用 -> 抛异常")
    void createSubscription_planDisabled_throws() {
        SubscriptionPlan disabled = plan("plan-disabled", new BigDecimal("1000"));
        disabled.setEnabled(false);
        when(planRepository.findByPlanId("plan-disabled")).thenReturn(Optional.of(disabled));
        assertThrows(IllegalArgumentException.class, () ->
                service.createSubscription(100L, "cust-1", "0xPayer", "0xPayee", "plan-disabled"));
    }

    @Test
    @DisplayName("cancelSubscription: 置 CANCELLED + cancelledAt")
    void cancelSubscription_success() {
        Subscription sub = activeSubscription("SUB-1", "plan-basic");
        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = service.cancelSubscription("SUB-1");

        assertEquals(SubscriptionStatus.CANCELLED, result.getStatus());
        assertNotNull(result.getCancelledAt());
    }

    @Test
    @DisplayName("cancelSubscription: 已取消幂等返回")
    void cancelSubscription_idempotent() {
        Subscription sub = activeSubscription("SUB-1", "plan-basic");
        sub.setStatus(SubscriptionStatus.CANCELLED);
        sub.setCancelledAt(LocalDateTime.now());
        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));

        Subscription result = service.cancelSubscription("SUB-1");

        assertEquals(SubscriptionStatus.CANCELLED, result.getStatus());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("upgradeSubscription: 按比例差价 > 0 -> 立即扣款 + 切换计划")
    void upgradeSubscription_immediateCharge() {
        Subscription sub = activeSubscription("SUB-1", "plan-basic");
        // 使用固定时间戳避免两次 now() 之间的时间差导致周期不精确
        LocalDateTime now = LocalDateTime.now();
        sub.setCurrentPeriodStart(now.minusDays(15));
        sub.setCurrentPeriodEnd(now.plusDays(15));

        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));
        when(planRepository.findByPlanId("plan-basic")).thenReturn(
                Optional.of(plan("plan-basic", new BigDecimal("1000"))));
        when(planRepository.findByPlanId("plan-pro")).thenReturn(
                Optional.of(plan("plan-pro", new BigDecimal("2000"))));
        when(chargeExecutor.charge(eq(sub), any(BigDecimal.class), anyString()))
                .thenReturn(ChargeResult.success("0xTxHash", "chain"));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = service.upgradeSubscription("SUB-1", "plan-pro");

        assertEquals("plan-pro", result.getPlanId());
        assertEquals("0xTxHash", result.getLastTxHash());
        // 验证扣款金额为按比例差价（约 1000 * 0.5 = 500）
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(chargeExecutor).charge(eq(sub), amountCaptor.capture(), anyString());
        BigDecimal chargedAmount = amountCaptor.getValue();
        assertTrue(chargedAmount.signum() > 0, "Should charge positive proration");
        // 周期 30 天，剩余 15 天，proration = (2000-1000) * 15/30 = 500
        // 允许 ±2 的误差（时钟漂移可能导致剩余天数 14 或 15）
        assertTrue(chargedAmount.doubleValue() > 466 && chargedAmount.doubleValue() < 534,
                "Proration should be ~500, got " + chargedAmount);
    }

    @Test
    @DisplayName("upgradeSubscription: 差价 0 -> 不扣款仅切换计划")
    void upgradeSubscription_zeroProration_noCharge() {
        Subscription sub = activeSubscription("SUB-1", "plan-basic");
        // 周期结束时刻升级 -> proration = 0
        sub.setCurrentPeriodStart(LocalDateTime.now().minusDays(30));
        sub.setCurrentPeriodEnd(LocalDateTime.now());

        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));
        when(planRepository.findByPlanId("plan-basic")).thenReturn(
                Optional.of(plan("plan-basic", new BigDecimal("1000"))));
        when(planRepository.findByPlanId("plan-pro")).thenReturn(
                Optional.of(plan("plan-pro", new BigDecimal("2000"))));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = service.upgradeSubscription("SUB-1", "plan-pro");

        assertEquals("plan-pro", result.getPlanId());
        verify(chargeExecutor, never()).charge(any(), any(), any());
    }

    @Test
    @DisplayName("upgradeSubscription: 新计划金额 < 旧计划 -> 抛异常（应使用 downgrade）")
    void upgradeSubscription_downgradeAmount_throws() {
        Subscription sub = activeSubscription("SUB-1", "plan-pro");
        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));
        when(planRepository.findByPlanId("plan-pro")).thenReturn(
                Optional.of(plan("plan-pro", new BigDecimal("2000"))));
        when(planRepository.findByPlanId("plan-basic")).thenReturn(
                Optional.of(plan("plan-basic", new BigDecimal("1000"))));

        assertThrows(IllegalArgumentException.class, () ->
                service.upgradeSubscription("SUB-1", "plan-basic"));
    }

    @Test
    @DisplayName("downgradeSubscription: 记录待生效计划，不立即扣款")
    void downgradeSubscription_schedulesForNextCycle() {
        Subscription sub = activeSubscription("SUB-1", "plan-pro");
        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));
        when(planRepository.findByPlanId("plan-pro")).thenReturn(
                Optional.of(plan("plan-pro", new BigDecimal("2000"))));
        when(planRepository.findByPlanId("plan-basic")).thenReturn(
                Optional.of(plan("plan-basic", new BigDecimal("1000"))));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Subscription result = service.downgradeSubscription("SUB-1", "plan-basic");

        // lastTxHash 暂存待降级计划 ID
        assertNotNull(result.getLastTxHash());
        assertTrue(result.getLastTxHash().startsWith("PENDING_DOWNGRADE:"));
        assertTrue(result.getLastTxHash().contains("plan-basic"));
        // 不立即扣款
        verify(chargeExecutor, never()).charge(any(), any(), any());
    }

    @Test
    @DisplayName("processBillingCycle: 扣款成功 -> 推进周期 + chargedCount++ + 重置 dunning")
    void processBillingCycle_success() {
        Subscription sub = activeSubscription("SUB-1", "plan-basic");
        sub.setDunningCount(2);
        sub.setStatus(SubscriptionStatus.PAST_DUE);

        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));
        when(planRepository.findByPlanId("plan-basic")).thenReturn(
                Optional.of(plan("plan-basic", new BigDecimal("1000"))));
        when(chargeExecutor.charge(eq(sub), eq(new BigDecimal("1000")), anyString()))
                .thenReturn(ChargeResult.success("0xTxHash", "chain"));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String txHash = service.processBillingCycle("SUB-1");

        assertEquals("0xTxHash", txHash);
        assertEquals(1, sub.getChargedCount());
        assertEquals(0, sub.getDunningCount());
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
        assertNotNull(sub.getCurrentPeriodStart());
        assertNotNull(sub.getCurrentPeriodEnd());
        assertEquals(sub.getCurrentPeriodEnd(), sub.getNextChargeAt());
    }

    @Test
    @DisplayName("processBillingCycle: 扣款失败 -> 进入 dunning")
    void processBillingCycle_failure_entersDunning() {
        Subscription sub = activeSubscription("SUB-1", "plan-basic");

        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));
        when(planRepository.findByPlanId("plan-basic")).thenReturn(
                Optional.of(plan("plan-basic", new BigDecimal("1000"))));
        when(chargeExecutor.charge(any(), any(), any()))
                .thenReturn(ChargeResult.failure("insufficient funds", "chain"));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String txHash = service.processBillingCycle("SUB-1");

        assertNull(txHash);
        assertEquals(1, sub.getDunningCount());
        assertEquals(SubscriptionStatus.PAST_DUE, sub.getStatus());
        // nextChargeAt 推进到 now + 1 天（dunning 第 1 次重试间隔）
        assertNotNull(sub.getNextChargeAt());
    }

    @Test
    @DisplayName("processBillingCycle: CANCELLED 状态 -> 返回 null 不扣款")
    void processBillingCycle_cancelled_noCharge() {
        Subscription sub = activeSubscription("SUB-1", "plan-basic");
        sub.setStatus(SubscriptionStatus.CANCELLED);
        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));

        String txHash = service.processBillingCycle("SUB-1");

        assertNull(txHash);
        verify(chargeExecutor, never()).charge(any(), any(), any());
    }

    @Test
    @DisplayName("processBillingCycle: 待生效降级在扣款前应用")
    void processBillingCycle_appliesPendingDowngrade() {
        Subscription sub = activeSubscription("SUB-1", "plan-pro");
        sub.setLastTxHash("PENDING_DOWNGRADE:plan-basic");

        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));
        // applyPendingDowngradeIfAny 会查找 "plan-basic" 并切换 planId
        // 之后 requirePlan(sub.getPlanId()) 也查找 "plan-basic"，不需要 mock "plan-pro"
        when(planRepository.findByPlanId("plan-basic")).thenReturn(
                Optional.of(plan("plan-basic", new BigDecimal("1000"))));
        when(chargeExecutor.charge(any(), any(), any()))
                .thenReturn(ChargeResult.success("0xTxHash", "chain"));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processBillingCycle("SUB-1");

        // 应该按新计划（plan-basic, 1000）扣款
        ArgumentCaptor<BigDecimal> amountCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(chargeExecutor).charge(any(), amountCaptor.capture(), anyString());
        assertEquals(new BigDecimal("1000"), amountCaptor.getValue());
        assertEquals("plan-basic", sub.getPlanId());
        // 扣款成功后 lastTxHash 为交易哈希（applyPendingDowngrade 清空后再被扣款结果覆盖）
        assertEquals("0xTxHash", sub.getLastTxHash());
    }

    @Test
    @DisplayName("convertTrialToActive: TRIAL + trialEnd 已过 -> 转正 + 首次扣款")
    void convertTrialToActive_success() {
        Subscription sub = activeSubscription("SUB-1", "plan-basic");
        sub.setStatus(SubscriptionStatus.TRIAL);
        sub.setTrialEnd(LocalDateTime.now().minusDays(1));

        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));
        when(planRepository.findByPlanId("plan-basic")).thenReturn(
                Optional.of(plan("plan-basic", new BigDecimal("1000"))));
        when(chargeExecutor.charge(any(), any(), any()))
                .thenReturn(ChargeResult.success("0xTxHash", "chain"));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean converted = service.convertTrialToActive("SUB-1");

        assertTrue(converted);
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
        assertEquals(1, sub.getChargedCount());
        assertEquals("0xTxHash", sub.getLastTxHash());
    }

    @Test
    @DisplayName("convertTrialToActive: 非 TRIAL 状态 -> 返回 false")
    void convertTrialToActive_notTrial_returnsFalse() {
        Subscription sub = activeSubscription("SUB-1", "plan-basic");
        sub.setStatus(SubscriptionStatus.ACTIVE);
        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));

        boolean converted = service.convertTrialToActive("SUB-1");

        assertFalse(converted);
        verify(chargeExecutor, never()).charge(any(), any(), any());
    }

    @Test
    @DisplayName("convertTrialToActive: TRIAL 但 trialEnd 未到 -> 返回 false")
    void convertTrialToActive_trialNotEnded_returnsFalse() {
        Subscription sub = activeSubscription("SUB-1", "plan-basic");
        sub.setStatus(SubscriptionStatus.TRIAL);
        sub.setTrialEnd(LocalDateTime.now().plusDays(5));
        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));

        boolean converted = service.convertTrialToActive("SUB-1");

        assertFalse(converted);
        verify(chargeExecutor, never()).charge(any(), any(), any());
    }

    @Test
    @DisplayName("getSubscription: 委托 repository")
    void getSubscription() {
        Subscription sub = activeSubscription("SUB-1", "plan-basic");
        when(subscriptionRepository.findBySubscriptionId("SUB-1")).thenReturn(Optional.of(sub));
        when(subscriptionRepository.findBySubscriptionId("SUB-ghost")).thenReturn(Optional.empty());

        assertTrue(service.getSubscription("SUB-1").isPresent());
        assertFalse(service.getSubscription("SUB-ghost").isPresent());
    }

    private Subscription activeSubscription(String subId, String planId) {
        Subscription s = new Subscription();
        s.setSubscriptionId(subId);
        s.setMerchantId(100L);
        s.setCustomerId("cust-1");
        s.setPayerAddress("0xPayer");
        s.setPayeeAddress("0xPayee");
        s.setPlanId(planId);
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setCurrentPeriodStart(LocalDateTime.now().minusDays(15));
        s.setCurrentPeriodEnd(LocalDateTime.now().plusDays(15));
        s.setNextChargeAt(LocalDateTime.now().minusMinutes(1));
        s.setDunningCount(0);
        s.setChargedCount(0);
        return s;
    }
}