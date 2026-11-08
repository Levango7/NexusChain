package org.nexus.gateway.reconciliation.link;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.reconciliation.ReconciliationDiffReport;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReconciliationAdjustmentService}.
 * Covers diff report processing, approval/rejection workflow, and fund adjustment execution.
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationAdjustmentServiceTest {

    @Mock private ReconciliationAdjustmentRepository adjustmentRepository;
    @Mock private AccountService accountService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ReconciliationAdjustmentService adjustmentService;

    @BeforeEach
    void setUp() {
        // @Value 注入的字段在单元测试中不生效，手动设置默认阈值
        adjustmentService.setAutoApproveThreshold(new BigDecimal("1000"));
    }

    // ==================== Test Case 1: 容差范围内自动调整 ====================

    @Test
    @DisplayName("processDiffReport: 差异 0.005 元在容差 0.01 元内 → AUTO_APPROVED 并执行资金调整")
    void processDiffReport_withinTolerance_autoApprovedAndExecuted() {
        // 设置容差阈值为 0.01 元
        adjustmentService.setAutoApproveThreshold(new BigDecimal("0.01"));

        // 构造 AMOUNT_MISMATCH 差错：渠道 100.005，内部 100.000，差异 0.005
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setId(1L);
        discrepancy.setDiscrepancyType(ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH);
        discrepancy.setTransactionId("TXN-001");
        discrepancy.setChannelAmount(new BigDecimal("100.005"));
        discrepancy.setInternalAmount(new BigDecimal("100.000"));
        discrepancy.setAmountDiff(new BigDecimal("0.005"));

        ReconciliationDiffReport diffReport = new ReconciliationDiffReport();
        diffReport.setDiscrepancies(List.of(discrepancy));

        when(adjustmentRepository.findByReferenceIn(any())).thenReturn(Collections.emptyList());
        when(adjustmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ReconciliationAdjustment> result = adjustmentService.processDiffReport(100L, diffReport, 1L);

        assertEquals(1, result.size());
        ReconciliationAdjustment adjustment = result.get(0);
        assertEquals(ApprovalStatus.AUTO_APPROVED, adjustment.getApprovalStatus());
        assertEquals(new BigDecimal("0.005"), adjustment.getAmount());
        // 渠道金额 > 内部金额 → CREDIT_ADJUST
        assertEquals(AdjustmentType.CREDIT_ADJUST, adjustment.getAdjustmentType());

        // 自动审批应立即执行资金调整：CREDIT_ADJUST → depositWithType
        verify(accountService).depositWithType(
                eq(100L), eq(new BigDecimal("0.005")), any(), eq(AccountOperationType.RECON_ADJUST));
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
    }

    // ==================== Test Case 2: 超出容差创建待审核 ====================

    @Test
    @DisplayName("processDiffReport: 差异 100 元超出容差 0.01 元 → PENDING_APPROVAL 不执行资金调整")
    void processDiffReport_exceedsTolerance_pendingApproval() {
        // 设置容差阈值为 0.01 元
        adjustmentService.setAutoApproveThreshold(new BigDecimal("0.01"));

        // 构造 AMOUNT_MISMATCH 差错：渠道 200.000，内部 100.000，差异 100.000
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setId(2L);
        discrepancy.setDiscrepancyType(ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH);
        discrepancy.setTransactionId("TXN-002");
        discrepancy.setChannelAmount(new BigDecimal("200.000"));
        discrepancy.setInternalAmount(new BigDecimal("100.000"));
        discrepancy.setAmountDiff(new BigDecimal("100.000"));

        ReconciliationDiffReport diffReport = new ReconciliationDiffReport();
        diffReport.setDiscrepancies(List.of(discrepancy));

        when(adjustmentRepository.findByReferenceIn(any())).thenReturn(Collections.emptyList());
        when(adjustmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ReconciliationAdjustment> result = adjustmentService.processDiffReport(100L, diffReport, 1L);

        assertEquals(1, result.size());
        ReconciliationAdjustment adjustment = result.get(0);
        assertEquals(ApprovalStatus.PENDING_APPROVAL, adjustment.getApprovalStatus());
        assertEquals(new BigDecimal("100.000"), adjustment.getAmount());

        // 待审批记录不应执行资金调整
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
    }

    // ==================== Test Case 3: 审批通过执行调整 ====================

    @Test
    @DisplayName("approveAdjustment: APPROVED 时调用 depositWithType 执行资金调整")
    void approveAdjustment_executesFundAdjustment() {
        // 构造 PENDING_APPROVAL 的 CREDIT_ADJUST 记录
        ReconciliationAdjustment adjustment = new ReconciliationAdjustment();
        adjustment.setId(10L);
        adjustment.setMerchantId(100L);
        adjustment.setAdjustmentType(AdjustmentType.CREDIT_ADJUST);
        adjustment.setAmount(new BigDecimal("500"));
        adjustment.setApprovalStatus(ApprovalStatus.PENDING_APPROVAL);
        adjustment.setExecuted(false);

        when(adjustmentRepository.findById(10L)).thenReturn(Optional.of(adjustment));
        when(adjustmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReconciliationAdjustment result = adjustmentService.approveAdjustment(10L, "admin");

        assertEquals(ApprovalStatus.APPROVED, result.getApprovalStatus());
        assertEquals("admin", result.getApprovedBy());
        assertNotNull(result.getApprovedAt());

        // 审批通过后应执行资金调整：CREDIT_ADJUST → depositWithType
        verify(accountService).depositWithType(
                eq(100L), eq(new BigDecimal("500")), any(), eq(AccountOperationType.RECON_ADJUST));
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
    }

    // ==================== Test Case 4: 审批拒绝标记 REJECTED ====================

    @Test
    @DisplayName("rejectAdjustment: 标记为 REJECTED 并记录拒绝原因，不执行资金调整")
    void rejectAdjustment_marksRejected() {
        // 构造 PENDING_APPROVAL 的 DEBIT_ADJUST 记录
        ReconciliationAdjustment adjustment = new ReconciliationAdjustment();
        adjustment.setId(20L);
        adjustment.setMerchantId(100L);
        adjustment.setAdjustmentType(AdjustmentType.DEBIT_ADJUST);
        adjustment.setAmount(new BigDecimal("500"));
        adjustment.setApprovalStatus(ApprovalStatus.PENDING_APPROVAL);
        adjustment.setExecuted(false);

        when(adjustmentRepository.findById(20L)).thenReturn(Optional.of(adjustment));
        when(adjustmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ReconciliationAdjustment result = adjustmentService.rejectAdjustment(20L, "admin", "金额异常需核实");

        assertEquals(ApprovalStatus.REJECTED, result.getApprovalStatus());
        assertEquals("admin", result.getApprovedBy());
        assertEquals("金额异常需核实", result.getRejectionReason());

        // 拒绝后不应执行资金调整
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
    }

    // ==================== Test Case 5: 金额超 1000 元必须审批 ====================

    @Test
    @DisplayName("processDiffReport: 金额 1500 元超过默认阈值 1000 元 → PENDING_APPROVAL 不能自动调整")
    void processDiffReport_amountOver1000_pendingApprovalNotAutoApproved() {
        // 默认阈值 1000 元（在 setUp 中设置），金额 1500 > 1000

        // 构造 AMOUNT_MISMATCH 差错：渠道 1500 / 内部 0 → 差额 1500 元
        // （注：2026-10-11 决策后 LONG_AMOUNT 已被本服务跳过，超阈值用例改用差额类型；
        //  LONG_AMOUNT 的跳过行为由 processDiffReport_longAmount_skipped 专门钉住）
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setId(3L);
        discrepancy.setDiscrepancyType(ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH);
        discrepancy.setTransactionId("TXN-003");
        discrepancy.setChannelAmount(new BigDecimal("1500"));
        discrepancy.setInternalAmount(BigDecimal.ZERO);
        discrepancy.setAmountDiff(new BigDecimal("1500"));

        ReconciliationDiffReport diffReport = new ReconciliationDiffReport();
        diffReport.setDiscrepancies(List.of(discrepancy));

        when(adjustmentRepository.findByReferenceIn(any())).thenReturn(Collections.emptyList());
        when(adjustmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ReconciliationAdjustment> result = adjustmentService.processDiffReport(100L, diffReport, 1L);

        assertEquals(1, result.size());
        ReconciliationAdjustment adjustment = result.get(0);
        // 金额超过 1000 元，不能自动审批，必须是 PENDING_APPROVAL
        assertEquals(ApprovalStatus.PENDING_APPROVAL, adjustment.getApprovalStatus());
        assertEquals(new BigDecimal("1500"), adjustment.getAmount());
        // AMOUNT_MISMATCH（渠道>内部）→ CREDIT_ADJUST
        assertEquals(AdjustmentType.CREDIT_ADJUST, adjustment.getAdjustmentType());

        // 超过阈值的记录不应自动执行资金调整
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
    }

    // ==================== 业务决策门禁：长款不再自动调账（避免与渠道退款双向动账） ====================

    @Test
    @DisplayName("processDiffReport: LONG_AMOUNT 被跳过 —— 不创建调整记录、零资金动作（2026-10-11 决策）")
    void processDiffReport_longAmount_skipped() {
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setId(9L);
        discrepancy.setDiscrepancyType(ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT);
        discrepancy.setTransactionId("TXN-LONG-1");
        discrepancy.setChannelAmount(new BigDecimal("500"));

        ReconciliationDiffReport diffReport = new ReconciliationDiffReport();
        diffReport.setDiscrepancies(List.of(discrepancy));

        when(adjustmentRepository.findByReferenceIn(any())).thenReturn(Collections.emptyList());

        List<ReconciliationAdjustment> result = adjustmentService.processDiffReport(100L, diffReport, 1L);

        assertTrue(result.isEmpty(), "长款不应产生自动调账记录（改由渠道退款路径处理）");
        verify(adjustmentRepository, never()).save(any());
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
    }

    // ==================== 日上限（2026-11-08）：enforce=false 只观测 ====================

    @Test
    @DisplayName("日上限 enforce=false：笔数超限仍 AUTO_APPROVED 并执行资金调整（行为未变），指标照常累加")
    void dailyLimit_enforceFalse_countOver_stillAutoApprovedAndExecuted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        adjustmentService.setMeterRegistry(registry);
        adjustmentService.setEnforceDailyLimits(false);
        adjustmentService.setDailyMaxCount(5);
        adjustmentService.setDailyMaxTotal(new BigDecimal("3000"));

        // 当日已有 5 笔 AUTO_APPROVED（累计 500），本笔 10 元 → 命中笔数上限
        stubDailyApproved(recordsOf("100", "100", "100", "100", "100"));
        stubSaveEcho();

        List<ReconciliationAdjustment> result =
                adjustmentService.processDiffReport(100L, reportOf(mismatch(11L, "10", "0", "10")), 1L);

        assertEquals(1, result.size());
        assertEquals(ApprovalStatus.AUTO_APPROVED, result.get(0).getApprovalStatus(),
                "enforce=false 是硬要求：超限也不得改变既有自动审批行为");
        verify(accountService).depositWithType(
                eq(100L), eq(new BigDecimal("10")), any(), eq(AccountOperationType.RECON_ADJUST));
        // 观测基线：即使不拦截，指标也必须累加
        assertEquals(1.0, registry.counter("nexus_reconciliation_adjustment_over_limit_total",
                "reason", "count").count(), 0.001);
        assertNull(registry.find("nexus_reconciliation_adjustment_over_limit_total")
                .tag("reason", "total").counter(), "累计未超限，不应打 total 计数");
    }

    // ==================== 日上限：enforce=true 转人工审批 ====================

    @Test
    @DisplayName("日上限 enforce=true：笔数超限 → PENDING_APPROVAL 且不执行资金调整")
    void dailyLimit_enforceTrue_countOver_pendingAndNoExecution() {
        adjustmentService.setEnforceDailyLimits(true);
        adjustmentService.setDailyMaxCount(5);
        adjustmentService.setDailyMaxTotal(new BigDecimal("3000"));

        stubDailyApproved(recordsOf("100", "100", "100", "100", "100"));
        stubSaveEcho();

        List<ReconciliationAdjustment> result =
                adjustmentService.processDiffReport(100L, reportOf(mismatch(12L, "10", "0", "10")), 1L);

        assertEquals(ApprovalStatus.PENDING_APPROVAL, result.get(0).getApprovalStatus());
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
    }

    @Test
    @DisplayName("日上限 enforce=true：当日累计超限 → PENDING_APPROVAL 且不执行资金调整")
    void dailyLimit_enforceTrue_totalOver_pendingAndNoExecution() {
        adjustmentService.setEnforceDailyLimits(true);
        adjustmentService.setDailyMaxCount(5);
        adjustmentService.setDailyMaxTotal(new BigDecimal("3000"));

        // 当日仅 1 笔（未超笔数），但累计 2900 + 本笔 200 = 3100 > 3000
        stubDailyApproved(recordsOf("2900"));
        stubSaveEcho();

        List<ReconciliationAdjustment> result =
                adjustmentService.processDiffReport(100L, reportOf(mismatch(13L, "200", "0", "200")), 1L);

        assertEquals(ApprovalStatus.PENDING_APPROVAL, result.get(0).getApprovalStatus());
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
    }

    // ==================== 计数口径：跨日重置 / 只算 AUTO_APPROVED ====================

    @Test
    @DisplayName("日上限：跨日重置 —— 昨日 20 笔不在今日区间内，今日第 1 笔放行（区间左闭右开）")
    void dailyLimit_crossDayReset_firstOfTodayPasses() {
        adjustmentService.setEnforceDailyLimits(true);
        adjustmentService.setDailyMaxCount(5);
        adjustmentService.setDailyMaxTotal(new BigDecimal("3000"));

        // 昨日 20 笔已被仓库查询的日区间排除 ⇒ 今日查询返回空
        stubDailyApproved(Collections.emptyList());
        stubSaveEcho();

        List<ReconciliationAdjustment> result =
                adjustmentService.processDiffReport(100L, reportOf(mismatch(14L, "10", "0", "10")), 1L);

        assertEquals(ApprovalStatus.AUTO_APPROVED, result.get(0).getApprovalStatus());
        verify(accountService).depositWithType(
                eq(100L), eq(new BigDecimal("10")), any(), eq(AccountOperationType.RECON_ADJUST));

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(adjustmentRepository).findByMerchantIdAndApprovalStatusAndCreatedAtBetween(
                eq(100L), eq(ApprovalStatus.AUTO_APPROVED), startCaptor.capture(), endCaptor.capture());
        LocalDateTime start = startCaptor.getValue();
        assertEquals(LocalDate.now().atStartOfDay(), start, "日界＝今日 00:00");
        assertEquals(start.plusDays(1), endCaptor.getValue(), "右开区间＝明日 00:00");
    }

    @Test
    @DisplayName("日上限：PENDING_APPROVAL/REJECTED 不占自动额度 —— 计数只按 AUTO_APPROVED 查询")
    void dailyLimit_onlyAutoApprovedCounted() {
        adjustmentService.setEnforceDailyLimits(true);
        adjustmentService.setDailyMaxCount(5);
        adjustmentService.setDailyMaxTotal(new BigDecimal("3000"));

        stubDailyApproved(Collections.emptyList());
        stubSaveEcho();

        adjustmentService.processDiffReport(100L, reportOf(mismatch(15L, "10", "0", "10")), 1L);

        ArgumentCaptor<ApprovalStatus> statusCaptor = ArgumentCaptor.forClass(ApprovalStatus.class);
        verify(adjustmentRepository).findByMerchantIdAndApprovalStatusAndCreatedAtBetween(
                eq(100L), statusCaptor.capture(), any(), any());
        assertEquals(ApprovalStatus.AUTO_APPROVED, statusCaptor.getValue(),
                "口径硬约束：只有 AUTO_APPROVED 计入当日笔数/累计");
    }

    // ==================== 与原单笔阈值的关系 ====================

    @Test
    @DisplayName("单笔 >1000 仍走原 PENDING_APPROVAL 路径，不进入日上限判据（不被新闸重复影响）")
    void singleAmountOver1000_originalPath_dailyQueryNotInvolved() {
        adjustmentService.setEnforceDailyLimits(true);
        adjustmentService.setDailyMaxCount(5);
        adjustmentService.setDailyMaxTotal(new BigDecimal("3000"));

        stubSaveEcho();

        List<ReconciliationAdjustment> result =
                adjustmentService.processDiffReport(100L, reportOf(mismatch(16L, "1500", "0", "1500")), 1L);

        assertEquals(ApprovalStatus.PENDING_APPROVAL, result.get(0).getApprovalStatus());
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        verify(adjustmentRepository, never())
                .findByMerchantIdAndApprovalStatusAndCreatedAtBetween(any(), any(), any(), any());
    }

    // ==================== fail-closed ====================

    @Test
    @DisplayName("日上限查询抛异常 → 按超限处理（fail-closed，有意选择），转 PENDING_APPROVAL 不动账")
    void dailyLimit_queryThrows_failClosedPending() {
        adjustmentService.setEnforceDailyLimits(true);
        adjustmentService.setDailyMaxCount(5);
        adjustmentService.setDailyMaxTotal(new BigDecimal("3000"));

        when(adjustmentRepository.findByMerchantIdAndApprovalStatusAndCreatedAtBetween(
                any(), any(), any(), any())).thenThrow(new RuntimeException("db down"));
        stubSaveEcho();

        List<ReconciliationAdjustment> result =
                adjustmentService.processDiffReport(100L, reportOf(mismatch(17L, "10", "0", "10")), 1L);

        assertEquals(ApprovalStatus.PENDING_APPROVAL, result.get(0).getApprovalStatus());
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
    }

    // ==================== 测试辅助 ====================

    private void stubDailyApproved(List<ReconciliationAdjustment> today) {
        when(adjustmentRepository.findByMerchantIdAndApprovalStatusAndCreatedAtBetween(
                eq(100L), eq(ApprovalStatus.AUTO_APPROVED), any(), any())).thenReturn(today);
    }

    private void stubSaveEcho() {
        when(adjustmentRepository.findByReferenceIn(any())).thenReturn(Collections.emptyList());
        when(adjustmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private List<ReconciliationAdjustment> recordsOf(String... amounts) {
        List<ReconciliationAdjustment> records = new ArrayList<>();
        for (String amount : amounts) {
            ReconciliationAdjustment record = new ReconciliationAdjustment();
            record.setMerchantId(100L);
            record.setAmount(new BigDecimal(amount));
            record.setApprovalStatus(ApprovalStatus.AUTO_APPROVED);
            records.add(record);
        }
        return records;
    }

    private ReconciliationDiffReport reportOf(ReconciliationDiscrepancy discrepancy) {
        ReconciliationDiffReport report = new ReconciliationDiffReport();
        report.setDiscrepancies(List.of(discrepancy));
        return report;
    }

    private ReconciliationDiscrepancy mismatch(long id, String channelAmount, String internalAmount, String diff) {
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setId(id);
        discrepancy.setDiscrepancyType(ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH);
        discrepancy.setTransactionId("TXN-DAILY-" + id);
        discrepancy.setChannelAmount(new BigDecimal(channelAmount));
        discrepancy.setInternalAmount(new BigDecimal(internalAmount));
        discrepancy.setAmountDiff(new BigDecimal(diff));
        return discrepancy;
    }
}
