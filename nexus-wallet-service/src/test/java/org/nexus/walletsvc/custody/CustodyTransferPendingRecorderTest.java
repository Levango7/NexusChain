package org.nexus.walletsvc.custody;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.sdk.wallet.WalletTransactionRequest;
import org.nexus.walletsvc.entity.CustodyTransferPendingEntity;
import org.nexus.walletsvc.entity.CustodyTransferPendingEntity.PendingStatus;
import org.nexus.walletsvc.repository.CustodyTransferPendingRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CustodyTransferPendingRecorder} 单元测试。
 *
 * <p>重点验两件事：落库内容足够人工核对（字段齐全、超长不炸整行），
 * 以及同一 {@code requestId} 重复登记是更新而不是插入（避免撞唯一键后丢留痕）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustodyTransferPendingRecorderTest {

    @Mock private CustodyTransferPendingRepository repository;

    private static WalletTransactionRequest request() {
        return new WalletTransactionRequest(WalletTransactionRequest.Type.SWEEP,
                "cold-custody", "0xabc123", new BigDecimal("400"), "NEX",
                "custody hot->cold sweep", "req-key-1");
    }

    private CustodyTransferPendingEntity saved() {
        ArgumentCaptor<CustodyTransferPendingEntity> captor =
                ArgumentCaptor.forClass(CustodyTransferPendingEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordsEveryFieldNeededForManualReconciliation() {
        when(repository.findByRequestKey("req-key-1")).thenReturn(Optional.empty());

        new CustodyTransferPendingRecorder(repository)
                .record(request(), PendingStatus.VERIFY_REQUIRED, "APR-1", "no txHash");

        CustodyTransferPendingEntity entity = saved();
        assertEquals("req-key-1", entity.getRequestKey());
        assertEquals("SWEEP", entity.getTransferType());
        assertEquals("custody hot->cold sweep", entity.getPurpose());
        assertEquals("0xabc123", entity.getAddress());
        assertEquals(0, new BigDecimal("400").compareTo(entity.getAmount()));
        assertEquals("NEX", entity.getCurrency());
        assertEquals(PendingStatus.VERIFY_REQUIRED, entity.getStatus());
        assertEquals("APR-1", entity.getApprovalId());
        assertEquals("no txHash", entity.getErrorNote());
    }

    @Test
    void truncatesOverlongValuesToColumnWidths() {
        // 超长必须截断：整行插入失败 = 这笔待人工核对的出金彻底没有留痕
        when(repository.findByRequestKey(anyString())).thenReturn(Optional.empty());
        WalletTransactionRequest longRequest = new WalletTransactionRequest(
                WalletTransactionRequest.Type.WITHDRAWAL, "cold-custody",
                "x".repeat(200), new BigDecimal("1"), "NEXUSCOIN-VERY-LONG", "y".repeat(200), "req-key-2");

        new CustodyTransferPendingRecorder(repository).record(longRequest,
                PendingStatus.APPROVAL_OPEN, "a".repeat(100), "b".repeat(600));

        CustodyTransferPendingEntity entity = saved();
        assertEquals(128, entity.getAddress().length());
        assertEquals(128, entity.getPurpose().length());
        assertEquals(16, entity.getCurrency().length());
        assertEquals(64, entity.getApprovalId().length());
        assertEquals(512, entity.getErrorNote().length());
    }

    @Test
    void sameRequestKeyUpdatesExistingRowInsteadOfInserting() {
        CustodyTransferPendingEntity existing = new CustodyTransferPendingEntity();
        existing.setRequestKey("req-key-1");
        existing.setStatus(PendingStatus.APPROVAL_OPEN);
        when(repository.findByRequestKey("req-key-1")).thenReturn(Optional.of(existing));

        new CustodyTransferPendingRecorder(repository)
                .record(request(), PendingStatus.VERIFY_REQUIRED, "APR-2", "hash lost after approval");

        CustodyTransferPendingEntity entity = saved();
        assertEquals(existing, entity);
        assertEquals(PendingStatus.VERIFY_REQUIRED, entity.getStatus());
        assertEquals("APR-2", entity.getApprovalId());
    }

    @Test
    void blankRequestKeyIsSkippedWithoutSaving() {
        // 没有幂等键就无法登记；此处不抛异常（资金决策已由调用方处理），只留 ERROR 日志
        WalletTransactionRequest keyless = new WalletTransactionRequest(
                WalletTransactionRequest.Type.SWEEP, "cold-custody", "0xabc",
                new BigDecimal("7"), "NEX", "memo", "   ");

        assertDoesNotThrow(() -> new CustodyTransferPendingRecorder(repository)
                .record(keyless, PendingStatus.VERIFY_REQUIRED, null, "no key"));
        verify(repository, never()).save(any(CustodyTransferPendingEntity.class));
    }

    @Test
    void nullRequestOrStatusIsSkippedWithoutSaving() {
        CustodyTransferPendingRecorder recorder = new CustodyTransferPendingRecorder(repository);

        assertDoesNotThrow(() -> recorder.record(null, PendingStatus.APPROVAL_OPEN, null, "note"));
        assertDoesNotThrow(() -> recorder.record(request(), null, null, "note"));
        verify(repository, never()).save(any(CustodyTransferPendingEntity.class));
    }

    @Test
    void optionalFieldsStayNullWhenAbsent() {
        when(repository.findByRequestKey("req-key-1")).thenReturn(Optional.empty());

        new CustodyTransferPendingRecorder(repository)
                .record(request(), PendingStatus.APPROVAL_OPEN, null, null);

        CustodyTransferPendingEntity entity = saved();
        assertNull(entity.getApprovalId());
        assertNull(entity.getErrorNote());
        assertTrue(entity.getTransferType().length() <= 16);
    }
}
