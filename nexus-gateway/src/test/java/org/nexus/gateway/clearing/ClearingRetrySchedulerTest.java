package org.nexus.gateway.clearing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.gateway.alert.AlertEvent;
import org.nexus.gateway.alert.AlertEventRepository;
import org.nexus.gateway.alert.AlertNotifier;
import org.nexus.gateway.alert.AlertRule;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ClearingRetryScheduler} 行为钉扎（审计发现 A5：清算失败重试无生产入口）。
 *
 * <p>断言的是"失败终于有出路"以及"出路不会二次动账"：只扫 {@code recordType=CLEARING}、
 * 同批次去重、逐批独立事务、重试后仍失败必告警、开关可关、告警失败不倒灌。</p>
 */
class ClearingRetrySchedulerTest {

    private ClearingSettlementRecordRepository recordRepository;
    private ClearingSettlementService clearingSettlementService;
    private AlertEventRepository alertEventRepository;
    private AlertNotifier emailNotifier;
    private AlertNotifier webhookNotifier;

    @BeforeEach
    void setUp() {
        recordRepository = mock(ClearingSettlementRecordRepository.class);
        clearingSettlementService = mock(ClearingSettlementService.class);
        alertEventRepository = mock(AlertEventRepository.class);
        emailNotifier = mock(AlertNotifier.class);
        webhookNotifier = mock(AlertNotifier.class);
        when(emailNotifier.channel()).thenReturn("email");
        when(webhookNotifier.channel()).thenReturn("webhook");
    }

    private ClearingRetryScheduler scheduler(boolean enabled) {
        return new ClearingRetryScheduler(recordRepository, clearingSettlementService,
                alertEventRepository, List.of(emailNotifier, webhookNotifier), enabled);
    }

    private ClearingSettlementRecord failedRecord(String batchNo) {
        ClearingSettlementRecord record = new ClearingSettlementRecord();
        record.setRecordType(ClearingSettlementService.RECORD_TYPE_CLEARING);
        record.setBatchNo(batchNo);
        record.setBookingStatus(BookingStatus.FAILED);
        return record;
    }

    private ClearingSettlementService.ClearingBatchResult result(String batchNo, int ok, int fail) {
        return new ClearingSettlementService.ClearingBatchResult(batchNo, ok, fail, List.of());
    }

    private void stubFailed(List<ClearingSettlementRecord> records) {
        when(recordRepository.findByRecordTypeAndBookingStatus(
                ClearingSettlementService.RECORD_TYPE_CLEARING, BookingStatus.FAILED))
                .thenReturn(records);
    }

    @Test
    @DisplayName("只扫 CLEARING 类型 —— 不把同表的划拨/调账记录当清算入账重试")
    void scansOnlyClearingRecordType() {
        stubFailed(List.of());
        scheduler(true).retryFailedClearingRecords();

        verify(recordRepository).findByRecordTypeAndBookingStatus(
                eq(ClearingSettlementService.RECORD_TYPE_CLEARING), eq(BookingStatus.FAILED));
        // 不带类型的旧取集方法不得再被使用（它在同批次里会捞到非清算记录）
        verify(recordRepository, never()).findByBookingStatus(any());
        verifyNoInteractions(clearingSettlementService);
    }

    @Test
    @DisplayName("按批次去重：同批次多条失败记录只重试一次")
    void deduplicatesByBatchNo() {
        stubFailed(List.of(failedRecord("B1"), failedRecord("B1"), failedRecord("B2")));
        when(clearingSettlementService.retryFailedRecords(anyString()))
                .thenAnswer(inv -> result(inv.getArgument(0), 1, 0));

        scheduler(true).retryFailedClearingRecords();

        verify(clearingSettlementService, times(1)).retryFailedRecords("B1");
        verify(clearingSettlementService, times(1)).retryFailedRecords("B2");
        // 全部成功 ⇒ 不告警
        verifyNoInteractions(alertEventRepository);
        verify(emailNotifier, never()).notify(any(AlertEvent.class));
    }

    @Test
    @DisplayName("重试后仍有失败 → 落库 CRITICAL 告警并扇出到全部通知器")
    void stillFailingRaisesCriticalAlert() {
        stubFailed(List.of(failedRecord("B1")));
        when(clearingSettlementService.retryFailedRecords("B1")).thenReturn(result("B1", 0, 3));

        scheduler(true).retryFailedClearingRecords();

        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(captor.capture());
        AlertEvent alert = captor.getValue();
        assertEquals(ClearingRetryScheduler.RULE_CLEARING_RETRY_EXHAUSTED, alert.getRuleName());
        assertEquals(ClearingRetryScheduler.METRIC_CLEARING_RETRY_FAILED, alert.getMetricName());
        assertEquals(AlertRule.Severity.CRITICAL, alert.getSeverity());
        assertEquals(3.0d, alert.getCurrentValue());
        assertTrue(alert.getMessage().contains("stillFailed=3"), alert.getMessage());

        verify(emailNotifier).notify(alert);
        verify(webhookNotifier).notify(alert);
    }

    @Test
    @DisplayName("单批抛异常不牵连其它批，且异常计入告警（不静默）")
    void batchFailureIsIsolatedAndAlerted() {
        stubFailed(List.of(failedRecord("B1"), failedRecord("B2")));
        when(clearingSettlementService.retryFailedRecords("B1"))
                .thenThrow(new RuntimeException("db down"));
        when(clearingSettlementService.retryFailedRecords("B2")).thenReturn(result("B2", 2, 0));

        assertDoesNotThrow(() -> scheduler(true).retryFailedClearingRecords());

        verify(clearingSettlementService).retryFailedRecords("B2");
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(captor.capture());
        assertTrue(captor.getValue().getMessage().contains("batchErrors=1"),
                captor.getValue().getMessage());
    }

    @Test
    @DisplayName("失败记录缺 batchNo → 不调重试且必告警（不静默丢弃）")
    void missingBatchNoStillAlerts() {
        ClearingSettlementRecord orphan = new ClearingSettlementRecord();
        orphan.setRecordType(ClearingSettlementService.RECORD_TYPE_CLEARING);
        orphan.setBookingStatus(BookingStatus.FAILED);
        stubFailed(List.of(orphan));

        scheduler(true).retryFailedClearingRecords();

        verifyNoInteractions(clearingSettlementService);
        ArgumentCaptor<AlertEvent> captor = ArgumentCaptor.forClass(AlertEvent.class);
        verify(alertEventRepository).save(captor.capture());
        assertTrue(captor.getValue().getMessage().contains("missingBatchNo=1"),
                captor.getValue().getMessage());
    }

    @Test
    @DisplayName("开关关闭 → 完全不扫库")
    void disabledSwitchSkipsEntirely() {
        scheduler(false).retryFailedClearingRecords();

        verifyNoInteractions(recordRepository);
        verifyNoInteractions(clearingSettlementService);
        verifyNoInteractions(alertEventRepository);
    }

    @Test
    @DisplayName("告警落库失败不得向调度主流程传播（旁路）")
    void alertFailureIsSwallowed() {
        stubFailed(List.of(failedRecord("B1")));
        when(clearingSettlementService.retryFailedRecords("B1")).thenReturn(result("B1", 0, 1));
        when(alertEventRepository.save(any(AlertEvent.class))).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> scheduler(true).retryFailedClearingRecords());

        // 落库已失败 ⇒ 不应再发"未持久化"的通知
        verify(emailNotifier, never()).notify(any(AlertEvent.class));
    }

    @Test
    @DisplayName("单个通知渠道抛异常不影响其它渠道")
    void oneFailingNotifierDoesNotBlockOthers() {
        stubFailed(List.of(failedRecord("B1")));
        when(clearingSettlementService.retryFailedRecords("B1")).thenReturn(result("B1", 0, 1));
        doThrow(new RuntimeException("smtp down")).when(emailNotifier).notify(any(AlertEvent.class));

        assertDoesNotThrow(() -> scheduler(true).retryFailedClearingRecords());

        verify(webhookNotifier).notify(any(AlertEvent.class));
    }
}
