package org.nexus.gateway.clearing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.MerchantAccount;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 清算入账重试的 <b>recordType 收窄</b>钉扎（2026-11-07 审计修复）。
 *
 * <p>原 {@code retryFailedRecords} 用 {@code findByBatchNoAndBookingStatus(batchNo, FAILED)}
 * 取集，不带 {@code recordType} 过滤；而 {@code clearing_settlement_record} 一表多型
 * （CLEARING / SETTLEMENT_TRANSFER / RECON_ADJUST / SUSPENSE_WRITEOFF）且**共享 batchNo**
 * ⇒ 重试会把同批次的划拨/调账/核销记录一并当清算入账去 {@code creditOnClearing}：
 * 对 DEBIT 方向是反向动账，对 {@code transferReference} 为空的行还会退化成与合法清算
 * 入账同一个幂等键而被跳过却标记 BOOKED（假 BOOKED）。</p>
 *
 * <p>该路径此前无生产调用方故未暴露，<b>接上定时重试即成为真实资金缺陷</b>，
 * 故本用例把"只取 CLEARING"钉死：连旧的无类型取集方法都不允许再被调用。</p>
 */
class ClearingRetryRecordTypeFilterTest {

    private ClearingSettlementRecordRepository recordRepository;
    private AccountService accountService;
    private ClearingSettlementService service;

    @BeforeEach
    void setUp() {
        recordRepository = mock(ClearingSettlementRecordRepository.class);
        accountService = mock(AccountService.class);
        service = new ClearingSettlementService(recordRepository, accountService,
                mock(ApplicationEventPublisher.class));
    }

    private ClearingSettlementRecord clearingRecord(Long merchantId, String amount, String reference) {
        ClearingSettlementRecord record = new ClearingSettlementRecord();
        record.setRecordType(ClearingSettlementService.RECORD_TYPE_CLEARING);
        record.setBatchNo("B1");
        record.setMerchantId(merchantId);
        record.setAmount(new BigDecimal(amount));
        record.setDirection("CREDIT");
        record.setBookingStatus(BookingStatus.FAILED);
        record.setTransferReference(reference);
        record.setErrorMessage("previous failure");
        return record;
    }

    @Test
    @DisplayName("取集必须按 recordType=CLEARING 收窄，且不再使用无类型过滤的旧查询")
    void queriesByRecordTypeOnly() {
        when(recordRepository.findByBatchNoAndRecordTypeAndBookingStatus(
                "B1", ClearingSettlementService.RECORD_TYPE_CLEARING, BookingStatus.FAILED))
                .thenReturn(List.of(clearingRecord(7L, "12.34", "CLR_B1_7")));
        MerchantAccount account = mock(MerchantAccount.class);
        when(account.getAccountId()).thenReturn("ACC-7");
        when(accountService.creditOnClearing(eq(7L), any(BigDecimal.class), anyString()))
                .thenReturn(account);

        ClearingSettlementService.ClearingBatchResult result = service.retryFailedRecords("B1");

        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        verify(recordRepository).findByBatchNoAndRecordTypeAndBookingStatus(
                "B1", ClearingSettlementService.RECORD_TYPE_CLEARING, BookingStatus.FAILED);
        // 关键断言：无类型过滤的旧取集方法不得再被使用
        verify(recordRepository, never()).findByBatchNoAndBookingStatus(anyString(), any());
    }

    @Test
    @DisplayName("重试成功 → BOOKED、清空错误信息、回填 accountId、保留幂等键")
    void successfulRetryBooksRecord() {
        ClearingSettlementRecord record = clearingRecord(7L, "12.34", "CLR_B1_7");
        when(recordRepository.findByBatchNoAndRecordTypeAndBookingStatus(
                "B1", ClearingSettlementService.RECORD_TYPE_CLEARING, BookingStatus.FAILED))
                .thenReturn(List.of(record));
        MerchantAccount account = mock(MerchantAccount.class);
        when(account.getAccountId()).thenReturn("ACC-7");
        when(accountService.creditOnClearing(eq(7L), any(BigDecimal.class), anyString()))
                .thenReturn(account);

        service.retryFailedRecords("B1");

        assertEquals(BookingStatus.BOOKED, record.getBookingStatus());
        assertEquals("ACC-7", record.getAccountId());
        assertNull(record.getErrorMessage());
        verify(recordRepository).save(record);
        // 幂等键沿用原 reference（不重新生成，避免同一笔入账换键重复记账）
        verify(accountService).creditOnClearing(eq(7L), any(BigDecimal.class), eq("CLR_B1_7"));
    }

    @Test
    @DisplayName("重试仍失败 → 保持 FAILED 并写入错误信息（不假 BOOKED）")
    void failingRetryKeepsFailedStatus() {
        ClearingSettlementRecord record = clearingRecord(7L, "12.34", "CLR_B1_7");
        when(recordRepository.findByBatchNoAndRecordTypeAndBookingStatus(
                "B1", ClearingSettlementService.RECORD_TYPE_CLEARING, BookingStatus.FAILED))
                .thenReturn(List.of(record));
        when(accountService.creditOnClearing(eq(7L), any(BigDecimal.class), anyString()))
                .thenThrow(new IllegalStateException("account frozen"));

        ClearingSettlementService.ClearingBatchResult result = service.retryFailedRecords("B1");

        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertEquals(BookingStatus.FAILED, record.getBookingStatus());
        assertEquals("account frozen", record.getErrorMessage());
    }
}
