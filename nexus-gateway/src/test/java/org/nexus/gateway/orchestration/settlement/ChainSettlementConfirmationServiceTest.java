package org.nexus.gateway.orchestration.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.model.FinalityStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ChainSettlementConfirmationService 单元测试（Wave 9-C1-1）。
 *
 * <p>覆盖以下场景：
 * <ul>
 *   <li>initiateConfirmation — 创建确认记录、幂等校验、参数校验</li>
 *   <li>checkConfirmation — 查询链上确认状态、确认数达标→CONFIRMED、链不可达→超时检测</li>
 *   <li>updateFinalityStatus — 更新最终性状态</li>
 *   <li>handleConfirmationTimeout — 超时处理、重试、FAILED 标记</li>
 *   <li>manualRetry — 手动重试、FAILED→PENDING 重置</li>
 *   <li>scanPendingConfirmations — 定时扫描逻辑</li>
 * </ul>
 */
class ChainSettlementConfirmationServiceTest {

    private ChainRpcClient chainRpc;
    private FinalityService finalityService;
    private SettlementConfirmationRecordRepository repository;
    private ChainSettlementConfirmationService service;

    @BeforeEach
    void setUp() {
        chainRpc = mock(ChainRpcClient.class);
        finalityService = mock(FinalityService.class);
        repository = mock(SettlementConfirmationRecordRepository.class);
        service = new ChainSettlementConfirmationService(
                chainRpc, finalityService, repository, 30, 3);

        when(finalityService.getBlocksToFinalize()).thenReturn(12L);
    }

    // ==================== initiateConfirmation ====================

    @Test
    void initiateConfirmationCreatesPendingRecord() {
        when(repository.existsByPaymentId("pay-001")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> {
            SettlementConfirmationRecord r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.OPTIMISTIC, 2, 12, "ok"));

        SettlementConfirmationRecord record = service.initiateConfirmation("pay-001", "0xabc");

        assertEquals("pay-001", record.getPaymentId());
        assertEquals("0xabc", record.getTxHash());
        assertEquals(SettlementConfirmationStatus.PENDING, record.getStatus());
        assertEquals(12, record.getRequiredConfirmations());
        assertEquals(0, record.getRetryCount());
        assertNotNull(record.getCreatedAt());
        verify(repository).save(any());
    }

    @Test
    void initiateConfirmationIsIdempotent() {
        SettlementConfirmationRecord existing = new SettlementConfirmationRecord();
        existing.setPaymentId("pay-001");
        existing.setTxHash("0xabc");
        existing.setStatus(SettlementConfirmationStatus.PENDING);

        when(repository.existsByPaymentId("pay-001")).thenReturn(true);
        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(existing));

        SettlementConfirmationRecord record = service.initiateConfirmation("pay-001", "0xabc");

        assertEquals(existing, record);
        verify(repository, never()).save(any());
    }

    @Test
    void initiateConfirmationRejectsNullPaymentId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiateConfirmation(null, "0xabc"));
    }

    @Test
    void initiateConfirmationRejectsEmptyPaymentId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiateConfirmation("", "0xabc"));
    }

    @Test
    void initiateConfirmationRejectsNullTxHash() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiateConfirmation("pay-001", null));
    }

    @Test
    void initiateConfirmationRejectsEmptyTxHash() {
        assertThrows(IllegalArgumentException.class,
                () -> service.initiateConfirmation("pay-001", ""));
    }

    @Test
    void initiateConfirmationImmediatelyChecksChain() {
        when(repository.existsByPaymentId("pay-001")).thenReturn(false);
        when(repository.save(any())).thenAnswer(inv -> {
            SettlementConfirmationRecord r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.FINALIZED, 12, 12, "finalized"));

        // 由于 checkConfirmation 内部也会调用 repository.findByPaymentId 和 save
        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(createRecord("pay-001", "0xabc")));

        service.initiateConfirmation("pay-001", "0xabc");

        // 验证立即执行了链上查询
        verify(finalityService).getFinality("0xabc");
    }

    // ==================== checkConfirmation ====================

    @Test
    void checkConfirmationUpdatesConfirmations() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.OPTIMISTIC, 5, 12, "ok"));

        SettlementConfirmationRecord result = service.checkConfirmation("pay-001");

        assertEquals(5, result.getConfirmations());
        assertEquals(SettlementConfirmationStatus.PENDING, result.getStatus());
        assertNotNull(result.getLastCheckedAt());
    }

    @Test
    void checkConfirmationMarksConfirmedWhenFinalized() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.FINALIZED, 12, 12, "finalized"));

        SettlementConfirmationRecord result = service.checkConfirmation("pay-001");

        assertEquals(SettlementConfirmationStatus.CONFIRMED, result.getStatus());
        assertEquals(12, result.getConfirmations());
        assertNotNull(result.getConfirmedAt());
    }

    @Test
    void checkConfirmationSkipsAlreadyConfirmed() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.CONFIRMED);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));

        SettlementConfirmationRecord result = service.checkConfirmation("pay-001");

        assertEquals(SettlementConfirmationStatus.CONFIRMED, result.getStatus());
        verify(finalityService, never()).getFinality(any());
    }

    @Test
    void checkConfirmationSkipsFailed() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.FAILED);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));

        SettlementConfirmationRecord result = service.checkConfirmation("pay-001");

        assertEquals(SettlementConfirmationStatus.FAILED, result.getStatus());
        verify(finalityService, never()).getFinality(any());
    }

    @Test
    void checkConfirmationThrowsForUnknownPaymentId() {
        when(repository.findByPaymentId("unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.checkConfirmation("unknown"));
    }

    @Test
    void checkConfirmationHandlesChainError() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc")).thenThrow(new RuntimeException("RPC error"));

        SettlementConfirmationRecord result = service.checkConfirmation("pay-001");

        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().contains("Chain query failed"));
    }

    @Test
    void checkConfirmationMarksTimeoutWhenUnknownAndElapsed() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);
        // 设置创建时间为 35 分钟前（超过 30 分钟超时阈值）
        record.setCreatedAt(Instant.now().minus(35, ChronoUnit.MINUTES));

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.UNKNOWN, 0, 12, "unreachable"));

        SettlementConfirmationRecord result = service.checkConfirmation("pay-001");

        // 超时后第一次重试 → TIMED_OUT
        assertEquals(SettlementConfirmationStatus.TIMED_OUT, result.getStatus());
        assertEquals(1, result.getRetryCount());
    }

    // ==================== updateFinalityStatus ====================

    @Test
    void updateFinalityStatusToFinalizedSetsConfirmed() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateFinalityStatus("pay-001", FinalityStatus.FINALIZED);

        assertEquals(SettlementConfirmationStatus.CONFIRMED, record.getStatus());
        assertNotNull(record.getConfirmedAt());
    }

    @Test
    void updateFinalityStatusThrowsForUnknownPaymentId() {
        when(repository.findByPaymentId("unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.updateFinalityStatus("unknown", FinalityStatus.FINALIZED));
    }

    // ==================== handleConfirmationTimeout ====================

    @Test
    void handleConfirmationTimeoutMarksTimedOut() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);
        record.setCreatedAt(Instant.now().minus(35, ChronoUnit.MINUTES));

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.OPTIMISTIC, 3, 12, "still pending"));

        service.handleConfirmationTimeout("pay-001");

        assertEquals(SettlementConfirmationStatus.TIMED_OUT, record.getStatus());
        assertEquals(1, record.getRetryCount());
        assertNotNull(record.getErrorMessage());
    }

    @Test
    void handleConfirmationTimeoutMarksFailedAfterMaxRetries() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.TIMED_OUT);
        record.setCreatedAt(Instant.now().minus(120, ChronoUnit.MINUTES));
        record.setRetryCount(3); // 已达最大重试次数

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.OPTIMISTIC, 3, 12, "still pending"));

        service.handleConfirmationTimeout("pay-001");

        assertEquals(SettlementConfirmationStatus.FAILED, record.getStatus());
        assertEquals(4, record.getRetryCount());
    }

    @Test
    void handleConfirmationTimeoutSkipsConfirmed() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.CONFIRMED);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));

        service.handleConfirmationTimeout("pay-001");

        assertEquals(SettlementConfirmationStatus.CONFIRMED, record.getStatus());
        verify(repository, never()).save(any());
    }

    @Test
    void handleConfirmationTimeoutSkipsNotYetTimedOut() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);
        record.setCreatedAt(Instant.now().minus(5, ChronoUnit.MINUTES)); // 仅 5 分钟，未超时

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));

        service.handleConfirmationTimeout("pay-001");

        assertEquals(SettlementConfirmationStatus.PENDING, record.getStatus());
        verify(repository, never()).save(any());
    }

    @Test
    void handleConfirmationTimeoutAutoRetryConfirms() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);
        record.setCreatedAt(Instant.now().minus(35, ChronoUnit.MINUTES));

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.FINALIZED, 12, 12, "finalized on retry"));

        service.handleConfirmationTimeout("pay-001");

        assertEquals(SettlementConfirmationStatus.CONFIRMED, record.getStatus());
        assertNotNull(record.getConfirmedAt());
    }

    // ==================== manualRetry ====================

    @Test
    void manualRetryChecksChainAndUpdates() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);
        record.setRetryCount(0);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.OPTIMISTIC, 5, 12, "pending"));

        SettlementConfirmationRecord result = service.manualRetry("pay-001");

        assertEquals(1, result.getRetryCount());
        assertEquals(5, result.getConfirmations());
        assertEquals(SettlementConfirmationStatus.PENDING, result.getStatus());
    }

    @Test
    void manualRetryConfirmsWhenFinalized() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.FINALIZED, 12, 12, "finalized"));

        SettlementConfirmationRecord result = service.manualRetry("pay-001");

        assertEquals(SettlementConfirmationStatus.CONFIRMED, result.getStatus());
        assertNotNull(result.getConfirmedAt());
    }

    @Test
    void manualRetryResetsFailedAndConfirmsWhenFinalized() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.FAILED);
        record.setRetryCount(3);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.FINALIZED, 12, 12, "finalized"));

        SettlementConfirmationRecord result = service.manualRetry("pay-001");

        // FAILED → 重置为 PENDING → 重试后确认成功
        assertEquals(SettlementConfirmationStatus.CONFIRMED, result.getStatus());
        assertEquals(4, result.getRetryCount());
        assertNotNull(result.getConfirmedAt());
    }

    @Test
    void manualRetryResetsFailedButMarksFailedAgainWhenMaxRetriesExceeded() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.FAILED);
        record.setRetryCount(3);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.OPTIMISTIC, 3, 12, "pending"));

        SettlementConfirmationRecord result = service.manualRetry("pay-001");

        // FAILED → 重置为 PENDING → retryCount=4 >= maxRetryCount=3 → 再次 FAILED
        assertEquals(SettlementConfirmationStatus.FAILED, result.getStatus());
        assertEquals(4, result.getRetryCount());
    }

    @Test
    void manualRetrySkipsAlreadyConfirmed() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.CONFIRMED);

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));

        SettlementConfirmationRecord result = service.manualRetry("pay-001");

        assertEquals(SettlementConfirmationStatus.CONFIRMED, result.getStatus());
        verify(finalityService, never()).getFinality(any());
    }

    @Test
    void manualRetryMarksFailedAfterMaxRetries() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);
        record.setRetryCount(2); // 再重试一次就到 3

        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.OPTIMISTIC, 1, 12, "still pending"));

        SettlementConfirmationRecord result = service.manualRetry("pay-001");

        assertEquals(3, result.getRetryCount());
        assertEquals(SettlementConfirmationStatus.FAILED, result.getStatus());
    }

    @Test
    void manualRetryThrowsForUnknownPaymentId() {
        when(repository.findByPaymentId("unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.manualRetry("unknown"));
    }

    // ==================== scanPendingConfirmations ====================

    @Test
    void scanPendingConfirmationsProcessesPendingRecords() {
        SettlementConfirmationRecord record1 = createRecord("pay-001", "0xabc");
        record1.setStatus(SettlementConfirmationStatus.PENDING);
        SettlementConfirmationRecord record2 = createRecord("pay-002", "0xdef");
        record2.setStatus(SettlementConfirmationStatus.PENDING);

        when(repository.findByStatus(SettlementConfirmationStatus.PENDING))
                .thenReturn(List.of(record1, record2));
        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record1));
        when(repository.findByPaymentId("pay-002")).thenReturn(Optional.of(record2));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.OPTIMISTIC, 3, 12, "ok"));
        when(finalityService.getFinality("0xdef"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.FINALIZED, 12, 12, "finalized"));

        service.scanPendingConfirmations();

        verify(finalityService).getFinality("0xabc");
        verify(finalityService).getFinality("0xdef");
    }

    @Test
    void scanPendingConfirmationsDoesNothingWhenNoPending() {
        when(repository.findByStatus(SettlementConfirmationStatus.PENDING))
                .thenReturn(List.of());

        service.scanPendingConfirmations();

        verify(finalityService, never()).getFinality(any());
    }

    @Test
    void scanPendingConfirmationsHandlesTimeoutForOldRecords() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        record.setStatus(SettlementConfirmationStatus.PENDING);
        record.setCreatedAt(Instant.now().minus(35, ChronoUnit.MINUTES));

        when(repository.findByStatus(SettlementConfirmationStatus.PENDING))
                .thenReturn(List.of(record));
        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(finalityService.getFinality("0xabc"))
                .thenReturn(new FinalityService.FinalityInfo(FinalityStatus.OPTIMISTIC, 3, 12, "still pending"));

        service.scanPendingConfirmations();

        // 应该触发超时处理
        verify(finalityService, atLeast(1)).getFinality("0xabc");
    }

    // ==================== getConfirmation ====================

    @Test
    void getConfirmationReturnsRecord() {
        SettlementConfirmationRecord record = createRecord("pay-001", "0xabc");
        when(repository.findByPaymentId("pay-001")).thenReturn(Optional.of(record));

        Optional<SettlementConfirmationRecord> result = service.getConfirmation("pay-001");

        assertTrue(result.isPresent());
        assertEquals("pay-001", result.get().getPaymentId());
    }

    @Test
    void getConfirmationReturnsEmptyForUnknown() {
        when(repository.findByPaymentId("unknown")).thenReturn(Optional.empty());

        Optional<SettlementConfirmationRecord> result = service.getConfirmation("unknown");

        assertTrue(result.isEmpty());
    }

    // ==================== Helper ====================

    private SettlementConfirmationRecord createRecord(String paymentId, String txHash) {
        SettlementConfirmationRecord record = new SettlementConfirmationRecord();
        record.setId(1L);
        record.setPaymentId(paymentId);
        record.setTxHash(txHash);
        record.setConfirmations(0);
        record.setRequiredConfirmations(12);
        record.setStatus(SettlementConfirmationStatus.PENDING);
        record.setRetryCount(0);
        record.setCreatedAt(Instant.now());
        record.setLastCheckedAt(Instant.now());
        return record;
    }
}