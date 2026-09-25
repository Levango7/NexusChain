package org.nexus.gateway.reconciliation.link;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.reconciliation.ReconciliationDiffReport;
import org.nexus.gateway.reconciliation.ReconciliationDiscrepancy;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
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

        when(adjustmentRepository.findByReference(any())).thenReturn(Optional.empty());
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

        when(adjustmentRepository.findByReference(any())).thenReturn(Optional.empty());
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

        // 构造 LONG_AMOUNT 差错：渠道有 1500 元，内部无记录 → 长款 1500 元
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setId(3L);
        discrepancy.setDiscrepancyType(ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT);
        discrepancy.setTransactionId("TXN-003");
        discrepancy.setChannelAmount(new BigDecimal("1500"));

        ReconciliationDiffReport diffReport = new ReconciliationDiffReport();
        diffReport.setDiscrepancies(List.of(discrepancy));

        when(adjustmentRepository.findByReference(any())).thenReturn(Optional.empty());
        when(adjustmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ReconciliationAdjustment> result = adjustmentService.processDiffReport(100L, diffReport, 1L);

        assertEquals(1, result.size());
        ReconciliationAdjustment adjustment = result.get(0);
        // 金额超过 1000 元，不能自动审批，必须是 PENDING_APPROVAL
        assertEquals(ApprovalStatus.PENDING_APPROVAL, adjustment.getApprovalStatus());
        assertEquals(new BigDecimal("1500"), adjustment.getAmount());
        // LONG_AMOUNT → CREDIT_ADJUST
        assertEquals(AdjustmentType.CREDIT_ADJUST, adjustment.getAdjustmentType());

        // 超过阈值的记录不应自动执行资金调整
        verify(accountService, never()).depositWithType(any(), any(), any(), any());
        verify(accountService, never()).withdrawWithType(any(), any(), any(), any());
    }
}