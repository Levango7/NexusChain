package org.nexus.gateway.clearing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.MerchantAccount;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * ClearingSettlementService 单元测试 — 覆盖清算批次入账的核心场景。
 *
 * <p>测试场景：</p>
 * <ul>
 *   <li>清算入账正常 — 10 笔订单全部入账成功</li>
 *   <li>幂等跳过 — 同一 clearingOrderId 重复入账不产生重复流水</li>
 *   <li>账户冻结跳过 — 冻结账户入账失败记录错误信息</li>
 *   <li>入账失败 — status=FAILED，继续处理其他商户</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ClearingSettlementServiceTest {

    @Mock private ClearingSettlementRecordRepository recordRepository;
    @Mock private AccountService accountService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private ClearingSettlementService clearingSettlementService;

    // ==================== Test 1: 清算入账正常 ====================

    @Test
    @DisplayName("清算入账正常 — 10 笔订单全部入账成功")
    void processClearingBatch_normalCase_allBooked() {
        // 准备 10 笔商户结算明细
        List<ClearingBatchCompletedEvent.MerchantSettlementDetail> details = new ArrayList<>();
        for (long i = 1; i <= 10; i++) {
            details.add(new ClearingBatchCompletedEvent.MerchantSettlementDetail(
                    i, new BigDecimal("100.00"), new BigDecimal("5.00")));
        }

        // Mock accountService.creditOnClearing 对每个商户返回有效账户
        for (long i = 1; i <= 10; i++) {
            MerchantAccount account = new MerchantAccount();
            account.setAccountId("MA" + i);
            account.setMerchantId(i);
            when(accountService.creditOnClearing(eq(i), any(BigDecimal.class), eq("CLR_BATCH001_" + i)))
                    .thenReturn(account);
        }

        when(recordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 执行
        ClearingSettlementService.ClearingBatchResult result =
                clearingSettlementService.processClearingBatch("BATCH001", details);

        // 验证结果摘要
        assertEquals(10, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        assertEquals(10, result.getRecords().size());

        // 验证每条记录状态
        for (ClearingSettlementRecord record : result.getRecords()) {
            assertEquals(BookingStatus.BOOKED, record.getBookingStatus());
            assertNotNull(record.getAccountId());
            assertEquals("CLEARING_SETTLE", record.getOperationType());
            assertNotNull(record.getTransferReference());
            assertNotNull(record.getSettledAt());
        }

        verify(recordRepository, times(10)).save(any());
    }

    // ==================== Test 2: 幂等跳过 ====================

    @Test
    @DisplayName("幂等跳过 — 同一 clearingOrderId 重复入账不产生重复流水")
    void processClearingBatch_idempotentSkip_noDuplicateTransaction() {
        // 准备 1 笔商户结算明细
        List<ClearingBatchCompletedEvent.MerchantSettlementDetail> details = List.of(
                new ClearingBatchCompletedEvent.MerchantSettlementDetail(
                        100L, new BigDecimal("50.00"), new BigDecimal("2.50")));

        // Mock creditOnClearing 返回 null（幂等跳过：该 clearingOrderId 已入账）
        when(accountService.creditOnClearing(eq(100L), any(BigDecimal.class), eq("CLR_BATCH002_100")))
                .thenReturn(null);

        when(recordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 执行
        ClearingSettlementService.ClearingBatchResult result =
                clearingSettlementService.processClearingBatch("BATCH002", details);

        // 验证：幂等跳过也标记为 BOOKED，successCount 计入
        assertEquals(1, result.getSuccessCount());
        assertEquals(0, result.getFailCount());
        assertEquals(1, result.getRecords().size());

        ClearingSettlementRecord record = result.getRecords().get(0);
        assertEquals(BookingStatus.BOOKED, record.getBookingStatus());
        assertEquals("CLR_BATCH002_100", record.getTransferReference());
        // 幂等跳过时 account 为 null，accountId 不设置
        assertNull(record.getAccountId());

        // 验证 creditOnClearing 只调用一次，不会重复入账
        verify(accountService, times(1))
                .creditOnClearing(eq(100L), any(BigDecimal.class), eq("CLR_BATCH002_100"));
    }

    // ==================== Test 3: 账户冻结跳过 ====================

    @Test
    @DisplayName("账户冻结跳过 — 冻结账户入账失败记录错误信息")
    void processClearingBatch_frozenAccount_failedWithFrozenMessage() {
        // 准备 1 笔商户结算明细
        List<ClearingBatchCompletedEvent.MerchantSettlementDetail> details = List.of(
                new ClearingBatchCompletedEvent.MerchantSettlementDetail(
                        200L, new BigDecimal("80.00"), new BigDecimal("4.00")));

        // Mock creditOnClearing 抛出 IllegalStateException（账户冻结）
        when(accountService.creditOnClearing(eq(200L), any(BigDecimal.class), eq("CLR_BATCH003_200")))
                .thenThrow(new IllegalStateException("账户已冻结"));

        when(recordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 执行
        ClearingSettlementService.ClearingBatchResult result =
                clearingSettlementService.processClearingBatch("BATCH003", details);

        // 验证：账户冻结导致入账失败
        assertEquals(0, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertEquals(1, result.getRecords().size());

        ClearingSettlementRecord record = result.getRecords().get(0);
        assertEquals(BookingStatus.FAILED, record.getBookingStatus());
        assertEquals("账户已冻结", record.getErrorMessage());

        verify(recordRepository, times(1)).save(any());
    }

    // ==================== Test 4: 入账失败 — 继续处理其他商户 ====================

    @Test
    @DisplayName("入账失败 — status=FAILED，继续处理其他商户")
    void processClearingBatch_partialFailure_continuesProcessing() {
        // 准备 3 笔商户结算明细
        List<ClearingBatchCompletedEvent.MerchantSettlementDetail> details = List.of(
                new ClearingBatchCompletedEvent.MerchantSettlementDetail(
                        301L, new BigDecimal("100.00"), new BigDecimal("5.00")),
                new ClearingBatchCompletedEvent.MerchantSettlementDetail(
                        302L, new BigDecimal("200.00"), new BigDecimal("10.00")),
                new ClearingBatchCompletedEvent.MerchantSettlementDetail(
                        303L, new BigDecimal("300.00"), new BigDecimal("15.00")));

        // Mock：商户 301 入账失败，302 和 303 成功
        when(accountService.creditOnClearing(eq(301L), any(BigDecimal.class), eq("CLR_BATCH004_301")))
                .thenThrow(new RuntimeException("入账系统异常"));

        MerchantAccount account302 = new MerchantAccount();
        account302.setAccountId("MA302");
        account302.setMerchantId(302L);
        when(accountService.creditOnClearing(eq(302L), any(BigDecimal.class), eq("CLR_BATCH004_302")))
                .thenReturn(account302);

        MerchantAccount account303 = new MerchantAccount();
        account303.setAccountId("MA303");
        account303.setMerchantId(303L);
        when(accountService.creditOnClearing(eq(303L), any(BigDecimal.class), eq("CLR_BATCH004_303")))
                .thenReturn(account303);

        when(recordRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // 执行
        ClearingSettlementService.ClearingBatchResult result =
                clearingSettlementService.processClearingBatch("BATCH004", details);

        // 验证：1 笔失败，2 笔成功
        assertEquals(2, result.getSuccessCount());
        assertEquals(1, result.getFailCount());
        assertEquals(3, result.getRecords().size());

        // 验证失败记录
        ClearingSettlementRecord record301 = result.getRecords().get(0);
        assertEquals(BookingStatus.FAILED, record301.getBookingStatus());
        assertEquals("入账系统异常", record301.getErrorMessage());

        // 验证成功记录
        ClearingSettlementRecord record302 = result.getRecords().get(1);
        assertEquals(BookingStatus.BOOKED, record302.getBookingStatus());
        assertEquals("MA302", record302.getAccountId());

        ClearingSettlementRecord record303 = result.getRecords().get(2);
        assertEquals(BookingStatus.BOOKED, record303.getBookingStatus());
        assertEquals("MA303", record303.getAccountId());

        // 验证所有记录都被保存（失败和成功均保存）
        verify(recordRepository, times(3)).save(any());
    }
}