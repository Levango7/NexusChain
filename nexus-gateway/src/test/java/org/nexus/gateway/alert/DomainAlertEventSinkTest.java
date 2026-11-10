package org.nexus.gateway.alert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.gateway.apiversion.ApiVersionDeprecatedEvent;
import org.nexus.gateway.apiversion.ApiVersionPolicy;
import org.nexus.gateway.clearing.ClearingBatchCompletedEvent;
import org.nexus.gateway.event.AccountLinkageFailedEvent;
import org.nexus.gateway.escrow.EscrowStatus;
import org.nexus.gateway.escrow.EscrowStatusChangedEvent;
import org.nexus.gateway.escrow.PreAuthStatus;
import org.nexus.gateway.escrow.PreAuthStatusChangedEvent;
import org.nexus.gateway.sla.SlaBreachEvent;
import org.nexus.gateway.sla.SlaMeasurement;
import org.nexus.gateway.sla.SlaTarget;
import org.nexus.gateway.subscription.DunningNotificationEvent;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DomainAlertEventSink} 的行为钉扎。
 *
 * <p>写这个类的直接动因（2026-11-07 审计发现 C4）：本仓大量用例只断言
 * {@code verify(eventPublisher).publishEvent(any(XxxEvent.class))} ——
 * <b>只断言"事件发出了"，不断言"有人处理"</b>，于是发布者–消费者断链永远测不出来
 * （{@code SlaBreachEvent}/{@code EscrowStatusChangedEvent}/{@code DunningNotificationEvent}
 * 三个事件就是这样长期无人消费的）。故此处断言的是<b>消费端</b>行为：告警落库、
 * 扇出到各通知器、以及"旁路不得反向影响发布方"。</p>
 */
class DomainAlertEventSinkTest {

    private AlertEventRepository eventRepository;
    private AlertNotifier emailNotifier;
    private AlertNotifier webhookNotifier;
    private DomainAlertEventSink sink;

    @BeforeEach
    void setUp() {
        eventRepository = mock(AlertEventRepository.class);
        emailNotifier = mock(AlertNotifier.class);
        webhookNotifier = mock(AlertNotifier.class);
        when(emailNotifier.channel()).thenReturn("email");
        when(webhookNotifier.channel()).thenReturn("webhook");
        sink = new DomainAlertEventSink(eventRepository, List.of(emailNotifier, webhookNotifier));
    }

    private AccountLinkageFailedEvent linkageFailed() {
        return new AccountLinkageFailedEvent(
                this, 42L, "PAY-1", "100.00", "wallet-service timeout",
                AccountLinkageFailedEvent.LinkageType.CREDIT_ON_PAYMENT);
    }

    @Test
    @DisplayName("账户联动失败 → CRITICAL 告警落库并扇出到全部通知器")
    void accountLinkageFailedRaisesCriticalAlert() {
        sink.onAccountLinkageFailed(linkageFailed());

        AlertEvent saved = captureSaved();
        assertEquals(DomainAlertEventSink.RULE_ACCOUNT_LINKAGE_FAILED, saved.getRuleName());
        assertEquals(AlertRule.Severity.CRITICAL, saved.getSeverity());
        assertEquals("account.linkage.failed", saved.getMetricName());
        // 离散事实用计数语义：发生 1 次 > 阈值 0
        assertEquals(1.0d, saved.getCurrentValue());
        assertEquals(0.0d, saved.getThreshold());
        assertNotNull(saved.getTimestamp());
        assertTrue(saved.getMessage().contains("CREDIT_ON_PAYMENT"), saved.getMessage());
        assertTrue(saved.getMessage().contains("PAY-1"), saved.getMessage());

        verify(emailNotifier).notify(saved);
        verify(webhookNotifier).notify(saved);
    }

    @Test
    @DisplayName("催收：SUSPEND → CRITICAL；NOTIFY/RETRY → WARN")
    void dunningSeverityFollowsAction() {
        sink.onDunningNotification(new DunningNotificationEvent(
                this, "SUB-9", 3, DunningNotificationEvent.Action.SUSPEND, LocalDateTime.now()));
        assertEquals(AlertRule.Severity.CRITICAL, captureSaved().getSeverity());

        sink.onDunningNotification(new DunningNotificationEvent(
                this, "SUB-9", 1, DunningNotificationEvent.Action.RETRY, LocalDateTime.now()));
        assertEquals(AlertRule.Severity.WARN, captureSaved().getSeverity());
    }

    @Test
    @DisplayName("SLA 未达标 → WARN，文案含实测量与目标值")
    void slaBreachRaisesWarnWithMeasuredAndTarget() {
        SlaTarget target = mock(SlaTarget.class);
        when(target.getName()).thenReturn("payment-confirm-p99");
        when(target.getMetricName()).thenReturn("nexus.payments.confirm.p99");
        when(target.getTargetValue()).thenReturn(3.0d);

        SlaMeasurement measurement = mock(SlaMeasurement.class);
        when(measurement.getMeasuredValue()).thenReturn(5.5d);
        when(measurement.getTargetValue()).thenReturn(3.0d);

        sink.onSlaBreach(new SlaBreachEvent(this, target, measurement));

        AlertEvent saved = captureSaved();
        assertEquals(DomainAlertEventSink.RULE_SLA_BREACH, saved.getRuleName());
        assertEquals(AlertRule.Severity.WARN, saved.getSeverity());
        assertEquals("nexus.payments.confirm.p99", saved.getMetricName());
        assertTrue(saved.getMessage().contains("payment-confirm-p99"), saved.getMessage());
        assertTrue(saved.getMessage().contains("5.5000"), saved.getMessage());
    }

    @Test
    @DisplayName("单个通知渠道抛异常不影响其它渠道")
    void oneFailingNotifierDoesNotBlockOthers() {
        doThrow(new RuntimeException("smtp down")).when(emailNotifier).notify(any(AlertEvent.class));

        assertDoesNotThrow(() -> sink.onAccountLinkageFailed(linkageFailed()));

        verify(emailNotifier).notify(any(AlertEvent.class));
        verify(webhookNotifier).notify(any(AlertEvent.class));
    }

    @Test
    @DisplayName("告警落库失败不得向发布方传播（旁路不能污染资金事务）")
    void repositoryFailureIsSwallowed() {
        when(eventRepository.save(any(AlertEvent.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> sink.onAccountLinkageFailed(linkageFailed()));

        // 落库已失败 ⇒ 不应再向通知器发"未持久化"的告警
        verify(emailNotifier, never()).notify(any(AlertEvent.class));
        verify(webhookNotifier, never()).notify(any(AlertEvent.class));
    }

    @Test
    @DisplayName("消息超长按 512 截断（AlertEvent.message 列宽）")
    void longMessageIsTruncated() {
        String longReason = "x".repeat(900);
        sink.onAccountLinkageFailed(new AccountLinkageFailedEvent(
                this, 1L, "PAY-2", "1.00", longReason,
                AccountLinkageFailedEvent.LinkageType.DEBIT_ON_REFUND));

        assertEquals(512, captureSaved().getMessage().length());
    }

    @Test
    @DisplayName("无通知器注册时空列表不报错（仍然落库）")
    void emptyNotifierListStillPersists() {
        DomainAlertEventSink solo = new DomainAlertEventSink(eventRepository, List.of());
        assertDoesNotThrow(() -> solo.onAccountLinkageFailed(linkageFailed()));
        verify(eventRepository, times(1)).save(any(AlertEvent.class));
    }

    @Test
    @DisplayName("API 版本治理：SUNSET/RETIRED → WARN，DEPRECATED → INFO，文案含版本号")
    void apiVersionDeprecatedSeverityFollowsStatus() {
        ApiVersionPolicy sunsetPolicy = new ApiVersionPolicy();
        sunsetPolicy.setVersion("v1");
        sunsetPolicy.setStatus(ApiVersionPolicy.VersionStatus.SUNSET);
        sink.onApiVersionDeprecated(new ApiVersionDeprecatedEvent(this, sunsetPolicy));
        AlertEvent sunset = captureSaved();
        assertEquals(DomainAlertEventSink.RULE_API_VERSION_DEPRECATED, sunset.getRuleName());
        assertEquals(AlertRule.Severity.WARN, sunset.getSeverity());
        assertTrue(sunset.getMessage().contains("v1"), sunset.getMessage());
        assertTrue(sunset.getMessage().contains("SUNSET"), sunset.getMessage());

        ApiVersionPolicy deprecatedPolicy = new ApiVersionPolicy();
        deprecatedPolicy.setVersion("v2");
        deprecatedPolicy.setStatus(ApiVersionPolicy.VersionStatus.DEPRECATED);
        sink.onApiVersionDeprecated(new ApiVersionDeprecatedEvent(this, deprecatedPolicy));
        AlertEvent deprecated = captureSaved();
        assertEquals(AlertRule.Severity.INFO, deprecated.getSeverity());
        assertTrue(deprecated.getMessage().contains("v2"), deprecated.getMessage());
    }

    @Test
    @DisplayName("担保/预授权状态变更 → INFO 留痕，文案含单号与迁移")
    void escrowAndPreAuthStatusChangesRaiseInfoAlerts() {
        sink.onEscrowStatusChanged(new EscrowStatusChangedEvent(
                this, 1L, "ORD-1", 9L, "ESC-1",
                EscrowStatus.FUNDED, EscrowStatus.RELEASED));
        AlertEvent escrow = captureSaved();
        assertEquals(DomainAlertEventSink.RULE_ESCROW_STATUS, escrow.getRuleName());
        assertEquals(AlertRule.Severity.INFO, escrow.getSeverity());
        assertTrue(escrow.getMessage().contains("ESC-1"), escrow.getMessage());
        assertTrue(escrow.getMessage().contains("FUNDED→RELEASED"), escrow.getMessage());

        sink.onPreAuthStatusChanged(new PreAuthStatusChangedEvent(
                this, 2L, "ORD-2", 9L, "PA-1",
                PreAuthStatus.AUTHORIZED, PreAuthStatus.CAPTURED));
        AlertEvent preauth = captureSaved();
        assertEquals(DomainAlertEventSink.RULE_PREAUTH_STATUS, preauth.getRuleName());
        assertEquals(AlertRule.Severity.INFO, preauth.getSeverity());
        assertTrue(preauth.getMessage().contains("PA-1"), preauth.getMessage());
        assertTrue(preauth.getMessage().contains("AUTHORIZED→CAPTURED"), preauth.getMessage());
    }

    @Test
    @DisplayName("清算批次完成 → INFO 留痕，文案含批次号/订单数/净额（只读通知，不记账）")
    void clearingBatchCompletedRaisesInfoAlert() {
        sink.onClearingBatchCompleted(new ClearingBatchCompletedEvent(
                this, "BATCH-77", 3, new BigDecimal("250.00"), LocalDateTime.now()));

        AlertEvent ev = captureSaved();
        assertEquals(DomainAlertEventSink.RULE_CLEARING_BATCH_COMPLETED, ev.getRuleName());
        assertEquals(AlertRule.Severity.INFO, ev.getSeverity());
        assertEquals("clearing.batch.completed", ev.getMetricName());
        assertTrue(ev.getMessage().contains("BATCH-77"), ev.getMessage());
        assertTrue(ev.getMessage().contains("orders=3"), ev.getMessage());
        assertTrue(ev.getMessage().contains("250.00"), ev.getMessage());

        verify(emailNotifier).notify(ev);
        verify(webhookNotifier).notify(ev);
    }

    private AlertEvent captureSaved() {
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(eventRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
