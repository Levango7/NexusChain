package org.nexus.gateway.subscription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link BillingCycleScheduler} 单元测试（P4-T8）。
 *
 * <p>覆盖周期扣款调度：试用期转正扫描、到期扣款扫描、异常隔离
 * （单个订阅失败不影响其他订阅）、空列表处理。</p>
 */
@ExtendWith(MockitoExtension.class)
class BillingCycleSchedulerTest {

    @Mock private SubscriptionRepository subscriptionRepository;
    @Mock private SubscriptionService subscriptionService;

    private BillingCycleScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BillingCycleScheduler(subscriptionRepository, subscriptionService);
    }

    private Subscription trialSubscription(String subId) {
        Subscription s = new Subscription();
        s.setSubscriptionId(subId);
        s.setMerchantId(100L);
        s.setCustomerId("cust-1");
        s.setPayerAddress("0xPayer");
        s.setPayeeAddress("0xPayee");
        s.setPlanId("plan-basic");
        s.setStatus(SubscriptionStatus.TRIAL);
        s.setCurrentPeriodStart(LocalDateTime.now().minusDays(14));
        s.setCurrentPeriodEnd(LocalDateTime.now().plusDays(16));
        s.setTrialEnd(LocalDateTime.now().minusDays(1));
        s.setNextChargeAt(LocalDateTime.now().minusDays(1));
        s.setDunningCount(0);
        s.setChargedCount(0);
        return s;
    }

    private Subscription activeSubscription(String subId) {
        Subscription s = new Subscription();
        s.setSubscriptionId(subId);
        s.setMerchantId(100L);
        s.setCustomerId("cust-1");
        s.setPayerAddress("0xPayer");
        s.setPayeeAddress("0xPayee");
        s.setPlanId("plan-basic");
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setCurrentPeriodStart(LocalDateTime.now().minusDays(15));
        s.setCurrentPeriodEnd(LocalDateTime.now().plusDays(15));
        s.setNextChargeAt(LocalDateTime.now().minusMinutes(1));
        s.setDunningCount(0);
        s.setChargedCount(0);
        return s;
    }

    @Test
    @DisplayName("runOnce: 同时处理试用期转正与到期扣款")
    void runOnce_processesBothTrialAndBilling() {
        Subscription trialSub = trialSubscription("SUB-trial");
        Subscription activeSub = activeSubscription("SUB-active");

        when(subscriptionRepository.findByStatusAndNextChargeAtBefore(
                eq(SubscriptionStatus.TRIAL), any()))
                .thenReturn(List.of(trialSub));
        when(subscriptionRepository.findByStatusInAndNextChargeAtBefore(
                eq(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)), any()))
                .thenReturn(List.of(activeSub));

        when(subscriptionService.convertTrialToActive("SUB-trial")).thenReturn(true);
        when(subscriptionService.processBillingCycle("SUB-active")).thenReturn("0xTxHash");

        int processed = scheduler.runOnce();

        assertEquals(2, processed);
        verify(subscriptionService).convertTrialToActive("SUB-trial");
        verify(subscriptionService).processBillingCycle("SUB-active");
    }

    @Test
    @DisplayName("runOnce: 空列表返回 0")
    void runOnce_emptyLists() {
        when(subscriptionRepository.findByStatusAndNextChargeAtBefore(
                eq(SubscriptionStatus.TRIAL), any()))
                .thenReturn(List.of());
        when(subscriptionRepository.findByStatusInAndNextChargeAtBefore(
                eq(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)), any()))
                .thenReturn(List.of());

        int processed = scheduler.runOnce();
        assertEquals(0, processed);
        verify(subscriptionService, never()).convertTrialToActive(any());
        verify(subscriptionService, never()).processBillingCycle(any());
    }

    @Test
    @DisplayName("runOnce: 单个订阅抛异常不影响其他订阅（异常隔离）")
    void runOnce_exceptionIsolation() {
        Subscription s1 = activeSubscription("SUB-1");
        Subscription s2 = activeSubscription("SUB-2");
        Subscription s3 = activeSubscription("SUB-3");

        when(subscriptionRepository.findByStatusAndNextChargeAtBefore(
                eq(SubscriptionStatus.TRIAL), any()))
                .thenReturn(List.of());
        when(subscriptionRepository.findByStatusInAndNextChargeAtBefore(
                eq(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)), any()))
                .thenReturn(List.of(s1, s2, s3));

        // s2 抛异常，s1/s3 正常
        when(subscriptionService.processBillingCycle("SUB-1")).thenReturn("0xTx1");
        when(subscriptionService.processBillingCycle("SUB-2"))
                .thenThrow(new RuntimeException("db error"));
        when(subscriptionService.processBillingCycle("SUB-3")).thenReturn("0xTx3");

        int processed = scheduler.runOnce();

        // s2 抛异常不计入处理数（异常被隔离），s1/s3 成功 -> 2
        assertEquals(2, processed);
        verify(subscriptionService).processBillingCycle("SUB-1");
        verify(subscriptionService).processBillingCycle("SUB-2");
        verify(subscriptionService).processBillingCycle("SUB-3");
    }

    @Test
    @DisplayName("runOnce: 试用期转正异常隔离")
    void runOnce_trialConversionExceptionIsolation() {
        Subscription t1 = trialSubscription("SUB-t1");
        Subscription t2 = trialSubscription("SUB-t2");

        when(subscriptionRepository.findByStatusAndNextChargeAtBefore(
                eq(SubscriptionStatus.TRIAL), any()))
                .thenReturn(List.of(t1, t2));
        when(subscriptionRepository.findByStatusInAndNextChargeAtBefore(
                eq(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)), any()))
                .thenReturn(List.of());

        when(subscriptionService.convertTrialToActive("SUB-t1"))
                .thenThrow(new RuntimeException("plan not found"));
        when(subscriptionService.convertTrialToActive("SUB-t2")).thenReturn(true);

        int processed = scheduler.runOnce();

        // t1 异常但仍计入处理数（异常隔离），t2 成功
        assertEquals(1, processed);
        verify(subscriptionService).convertTrialToActive("SUB-t1");
        verify(subscriptionService).convertTrialToActive("SUB-t2");
    }

    @Test
    @DisplayName("runOnce: convertTrialToActive 返回 false 不计入转正数")
    void runOnce_trialConversionFalseNotCounted() {
        Subscription t1 = trialSubscription("SUB-t1");

        when(subscriptionRepository.findByStatusAndNextChargeAtBefore(
                eq(SubscriptionStatus.TRIAL), any()))
                .thenReturn(List.of(t1));
        when(subscriptionRepository.findByStatusInAndNextChargeAtBefore(
                eq(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)), any()))
                .thenReturn(List.of());

        // 返回 false（如试用期未到）
        when(subscriptionService.convertTrialToActive("SUB-t1")).thenReturn(false);

        int processed = scheduler.runOnce();
        assertEquals(0, processed);
    }

    @Test
    @DisplayName("processDueSubscriptions: 委托 runOnce 逻辑（@Scheduled 入口）")
    void processDueSubscriptions_scheduledEntry() {
        when(subscriptionRepository.findByStatusAndNextChargeAtBefore(
                eq(SubscriptionStatus.TRIAL), any()))
                .thenReturn(List.of());
        when(subscriptionRepository.findByStatusInAndNextChargeAtBefore(
                eq(List.of(SubscriptionStatus.ACTIVE, SubscriptionStatus.PAST_DUE)), any()))
                .thenReturn(List.of());

        // 不抛异常即可验证 @Scheduled 入口可调用
        scheduler.processDueSubscriptions();
    }
}