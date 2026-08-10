package org.nexus.gateway.subscription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link DunningManager} 单元测试（P4-T8）。
 *
 * <p>覆盖 dunning 策略：第 1-3 次失败 RETRY（间隔 1/3/7 天）、
 * 第 4 次失败 NOTIFY、第 5 次失败 SUSPEND，以及扣款成功重置、
 * shouldAttemptCharge 判断。</p>
 */
@ExtendWith(MockitoExtension.class)
class DunningManagerTest {

    @Mock private ApplicationEventPublisher eventPublisher;

    private SubscriptionProperties properties;
    private DunningManager dunningManager;

    @BeforeEach
    void setUp() {
        properties = new SubscriptionProperties();
        // 默认配置：maxRetries=3, retryIntervals=1d,3d,7d, suspendAfter=5
        dunningManager = new DunningManager(eventPublisher, properties);
    }

    private Subscription activeSubscription() {
        Subscription s = new Subscription();
        s.setSubscriptionId("SUB-001");
        s.setMerchantId(100L);
        s.setCustomerId("cust-1");
        s.setPayerAddress("0xPayer");
        s.setPayeeAddress("0xPayee");
        s.setPlanId("plan-basic");
        s.setStatus(SubscriptionStatus.ACTIVE);
        s.setCurrentPeriodStart(LocalDateTime.now().minusDays(15));
        s.setCurrentPeriodEnd(LocalDateTime.now().plusDays(15));
        s.setNextChargeAt(LocalDateTime.now());
        s.setDunningCount(0);
        s.setChargedCount(0);
        return s;
    }

    @Test
    @DisplayName("handleChargeFailure: 第 1 次失败 -> RETRY，间隔 1 天，状态 PAST_DUE")
    void firstFailure_retryInterval1Day() {
        Subscription sub = activeSubscription();
        LocalDateTime before = LocalDateTime.now();

        DunningNotificationEvent.Action action = dunningManager.handleChargeFailure(sub);

        assertEquals(DunningNotificationEvent.Action.RETRY, action);
        assertEquals(1, sub.getDunningCount());
        assertEquals(SubscriptionStatus.PAST_DUE, sub.getStatus());
        assertNotNull(sub.getNextChargeAt());
        // nextRetryAt ≈ now + 1 day
        assertTrue(sub.getNextChargeAt().isAfter(before.plusDays(1).minusMinutes(1)));
        assertTrue(sub.getNextChargeAt().isBefore(before.plusDays(1).plusMinutes(1)));

        // 验证事件发布
        ArgumentCaptor<DunningNotificationEvent> captor = ArgumentCaptor.forClass(DunningNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        DunningNotificationEvent event = captor.getValue();
        assertEquals("SUB-001", event.getSubscriptionId());
        assertEquals(1, event.getAttemptCount());
        assertEquals(DunningNotificationEvent.Action.RETRY, event.getAction());
    }

    @Test
    @DisplayName("handleChargeFailure: 第 2 次失败 -> RETRY，间隔 3 天")
    void secondFailure_retryInterval3Days() {
        Subscription sub = activeSubscription();
        sub.setDunningCount(1);
        sub.setStatus(SubscriptionStatus.PAST_DUE);

        DunningNotificationEvent.Action action = dunningManager.handleChargeFailure(sub);

        assertEquals(DunningNotificationEvent.Action.RETRY, action);
        assertEquals(2, sub.getDunningCount());
        // nextRetryAt ≈ now + 3 days
        LocalDateTime expected = LocalDateTime.now().plusDays(3);
        assertTrue(sub.getNextChargeAt().isAfter(expected.minusMinutes(1)));
        assertTrue(sub.getNextChargeAt().isBefore(expected.plusMinutes(1)));
    }

    @Test
    @DisplayName("handleChargeFailure: 第 3 次失败 -> RETRY，间隔 7 天")
    void thirdFailure_retryInterval7Days() {
        Subscription sub = activeSubscription();
        sub.setDunningCount(2);
        sub.setStatus(SubscriptionStatus.PAST_DUE);

        DunningNotificationEvent.Action action = dunningManager.handleChargeFailure(sub);

        assertEquals(DunningNotificationEvent.Action.RETRY, action);
        assertEquals(3, sub.getDunningCount());
        LocalDateTime expected = LocalDateTime.now().plusDays(7);
        assertTrue(sub.getNextChargeAt().isAfter(expected.minusMinutes(1)));
        assertTrue(sub.getNextChargeAt().isBefore(expected.plusMinutes(1)));
    }

    @Test
    @DisplayName("handleChargeFailure: 第 4 次失败 -> NOTIFY（超过 maxRetries=3）")
    void fourthFailure_notifyMerchant() {
        Subscription sub = activeSubscription();
        sub.setDunningCount(3);
        sub.setStatus(SubscriptionStatus.PAST_DUE);

        DunningNotificationEvent.Action action = dunningManager.handleChargeFailure(sub);

        assertEquals(DunningNotificationEvent.Action.NOTIFY, action);
        assertEquals(4, sub.getDunningCount());
        // 仍为 PAST_DUE（未暂停），继续重试
        assertEquals(SubscriptionStatus.PAST_DUE, sub.getStatus());
        assertNotNull(sub.getNextChargeAt());

        ArgumentCaptor<DunningNotificationEvent> captor = ArgumentCaptor.forClass(DunningNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(DunningNotificationEvent.Action.NOTIFY, captor.getValue().getAction());
    }

    @Test
    @DisplayName("handleChargeFailure: 第 5 次失败 -> SUSPEND（达到 suspendAfter=5）")
    void fifthFailure_suspendSubscription() {
        Subscription sub = activeSubscription();
        sub.setDunningCount(4);
        sub.setStatus(SubscriptionStatus.PAST_DUE);

        DunningNotificationEvent.Action action = dunningManager.handleChargeFailure(sub);

        assertEquals(DunningNotificationEvent.Action.SUSPEND, action);
        assertEquals(5, sub.getDunningCount());
        assertEquals(SubscriptionStatus.PAUSED, sub.getStatus());
        assertNotNull(sub.getPausedAt());

        ArgumentCaptor<DunningNotificationEvent> captor = ArgumentCaptor.forClass(DunningNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(DunningNotificationEvent.Action.SUSPEND, captor.getValue().getAction());
    }

    @Test
    @DisplayName("handleChargeFailure: 自定义 suspendAfter=3，第 3 次失败即 SUSPEND")
    void customSuspendAfter() {
        properties.getDunning().setSuspendAfter(3);
        properties.getDunning().setMaxRetries(2);

        Subscription sub = activeSubscription();
        sub.setDunningCount(2);

        DunningNotificationEvent.Action action = dunningManager.handleChargeFailure(sub);

        assertEquals(DunningNotificationEvent.Action.SUSPEND, action);
        assertEquals(SubscriptionStatus.PAUSED, sub.getStatus());
    }

    @Test
    @DisplayName("handleChargeSuccess: 重置 dunningCount，PAST_DUE 恢复为 ACTIVE")
    void handleChargeSuccess_resetsAndRecovers() {
        Subscription sub = activeSubscription();
        sub.setDunningCount(3);
        sub.setStatus(SubscriptionStatus.PAST_DUE);

        dunningManager.handleChargeSuccess(sub);

        assertEquals(0, sub.getDunningCount());
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
    }

    @Test
    @DisplayName("handleChargeSuccess: ACTIVE 状态保持不变")
    void handleChargeSuccess_activeStaysActive() {
        Subscription sub = activeSubscription();
        sub.setDunningCount(0);

        dunningManager.handleChargeSuccess(sub);

        assertEquals(0, sub.getDunningCount());
        assertEquals(SubscriptionStatus.ACTIVE, sub.getStatus());
    }

    @Test
    @DisplayName("shouldAttemptCharge: ACTIVE + nextChargeAt 已到 -> true")
    void shouldAttemptCharge_activeDue() {
        Subscription sub = activeSubscription();
        sub.setNextChargeAt(LocalDateTime.now().minusMinutes(1));
        assertTrue(dunningManager.shouldAttemptCharge(sub));
    }

    @Test
    @DisplayName("shouldAttemptCharge: ACTIVE + nextChargeAt 未到 -> false")
    void shouldAttemptCharge_activeNotDue() {
        Subscription sub = activeSubscription();
        sub.setNextChargeAt(LocalDateTime.now().plusDays(1));
        assertFalse(dunningManager.shouldAttemptCharge(sub));
    }

    @Test
    @DisplayName("shouldAttemptCharge: PAST_DUE + nextChargeAt 已到 -> true")
    void shouldAttemptCharge_pastDueDue() {
        Subscription sub = activeSubscription();
        sub.setStatus(SubscriptionStatus.PAST_DUE);
        sub.setNextChargeAt(LocalDateTime.now().minusMinutes(1));
        assertTrue(dunningManager.shouldAttemptCharge(sub));
    }

    @Test
    @DisplayName("shouldAttemptCharge: TRIAL/PAUSED/CANCELLED -> false")
    void shouldAttemptCharge_nonChargeableStatuses() {
        Subscription sub = activeSubscription();
        sub.setNextChargeAt(LocalDateTime.now().minusMinutes(1));

        sub.setStatus(SubscriptionStatus.TRIAL);
        assertFalse(dunningManager.shouldAttemptCharge(sub));

        sub.setStatus(SubscriptionStatus.PAUSED);
        assertFalse(dunningManager.shouldAttemptCharge(sub));

        sub.setStatus(SubscriptionStatus.CANCELLED);
        assertFalse(dunningManager.shouldAttemptCharge(sub));
    }

    @Test
    @DisplayName("parseRetryIntervals: 解析 1d,3d,7d -> [1,3,7]")
    void parseRetryIntervals() {
        assertEquals(java.util.List.of(1, 3, 7), properties.getDunning().parseRetryIntervals());
    }

    @Test
    @DisplayName("retryIntervalDays: 超出配置长度返回最后一个")
    void retryIntervalDays_overflow() {
        // 配置 [1,3,7]，第 10 次失败应返回最后一个 7
        assertEquals(7, properties.getDunning().retryIntervalDays(10));
    }

    @Test
    @DisplayName("handleChargeFailure: null 参数 -> 抛异常")
    void handleChargeFailure_null_throws() {
        assertThrows(IllegalArgumentException.class, () -> dunningManager.handleChargeFailure(null));
    }
}