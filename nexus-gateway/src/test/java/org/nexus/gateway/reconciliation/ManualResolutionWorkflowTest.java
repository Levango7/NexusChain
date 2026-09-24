package org.nexus.gateway.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ManualResolutionWorkflow 单元测试。
 *
 * <p>覆盖提交处理方案、审批通过（自动核销）、审批拒绝等核心场景。</p>
 */
class ManualResolutionWorkflowTest {

    private SuspenseAccountService suspenseAccountService;
    private SuspenseAccountRepository suspenseAccountRepository;
    private ManualResolutionWorkflow manualResolutionWorkflow;

    private static final Long MERCHANT_ID = 500L;
    private static final Long DISCREPANCY_ID = 100L;

    @BeforeEach
    void setUp() {
        suspenseAccountService = mock(SuspenseAccountService.class);
        suspenseAccountRepository = mock(SuspenseAccountRepository.class);
        manualResolutionWorkflow = new ManualResolutionWorkflow(
                suspenseAccountService, suspenseAccountRepository);
    }

    // ==================== submitResolutionProposal ====================

    @Test
    @DisplayName("submitResolutionProposal — PENDING 挂账提交方案成功")
    void submitProposalFromPending() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);
        account.setId(1L);

        when(suspenseAccountService.findSuspenseAccountById(1L))
                .thenReturn(Optional.of(account));
        when(suspenseAccountRepository.save(any(SuspenseAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SuspenseAccount result = manualResolutionWorkflow.submitResolutionProposal(
                1L, "Refund to customer", "operator01");

        assertEquals("Refund to customer", result.getProposal());
        assertEquals("operator01", result.getProposedBy());
        assertEquals(ManualResolutionWorkflow.PROPOSAL_PENDING, result.getProposalStatus());
        assertEquals(SuspenseAccount.SuspenseStatus.PENDING, result.getStatus());
    }

    @Test
    @DisplayName("submitResolutionProposal — RESOLVED 挂账不允许提交方案")
    void submitProposalFromResolvedThrows() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.RESOLVED);
        account.setId(1L);

        when(suspenseAccountService.findSuspenseAccountById(1L))
                .thenReturn(Optional.of(account));

        assertThrows(IllegalStateException.class,
                () -> manualResolutionWorkflow.submitResolutionProposal(
                        1L, "test", "operator01"));
    }

    @Test
    @DisplayName("submitResolutionProposal — WRITTEN_OFF 挂账不允许提交方案")
    void submitProposalFromWrittenOffThrows() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.WRITTEN_OFF);
        account.setId(1L);

        when(suspenseAccountService.findSuspenseAccountById(1L))
                .thenReturn(Optional.of(account));

        assertThrows(IllegalStateException.class,
                () -> manualResolutionWorkflow.submitResolutionProposal(
                        1L, "test", "operator01"));
    }

    @Test
    @DisplayName("submitResolutionProposal — 不存在的 ID 抛出异常")
    void submitProposalNotFoundThrows() {
        when(suspenseAccountService.findSuspenseAccountById(999L))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> manualResolutionWorkflow.submitResolutionProposal(
                        999L, "test", "operator01"));
    }

    // ==================== approveResolution ====================

    @Test
    @DisplayName("approveResolution — 审批通过后自动核销挂账")
    void approveResolutionAutoResolves() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);
        account.setId(1L);
        account.setProposal("Refund to customer");
        account.setProposedBy("operator01");
        account.setProposalStatus(ManualResolutionWorkflow.PROPOSAL_PENDING);

        // approveResolution 内部先 save 记录审批人，再调用 resolveSuspenseAccount
        SuspenseAccount resolvedAccount = createSuspenseAccount(SuspenseAccount.SuspenseStatus.RESOLVED);
        resolvedAccount.setId(1L);
        resolvedAccount.setResolutionNote("Approved by admin01: Refund to customer");

        when(suspenseAccountService.findSuspenseAccountById(1L))
                .thenReturn(Optional.of(account));
        when(suspenseAccountRepository.save(any(SuspenseAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(suspenseAccountService.resolveSuspenseAccount(eq(1L), anyString()))
                .thenReturn(resolvedAccount);

        SuspenseAccount result = manualResolutionWorkflow.approveResolution(1L, "admin01");

        // 验证审批人已记录
        assertEquals("admin01", account.getApprovedBy());
        assertEquals(ManualResolutionWorkflow.PROPOSAL_APPROVED, account.getProposalStatus());
        // 验证自动核销被调用
        verify(suspenseAccountService).resolveSuspenseAccount(eq(1L), anyString());
        // 返回的是核销后的记录
        assertEquals(SuspenseAccount.SuspenseStatus.RESOLVED, result.getStatus());
    }

    @Test
    @DisplayName("approveResolution — 未提交方案不允许审批")
    void approveWithoutProposalThrows() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);
        account.setId(1L);
        account.setProposalStatus(null); // 未提交方案

        when(suspenseAccountService.findSuspenseAccountById(1L))
                .thenReturn(Optional.of(account));

        assertThrows(IllegalStateException.class,
                () -> manualResolutionWorkflow.approveResolution(1L, "admin01"));
    }

    @Test
    @DisplayName("approveResolution — 已拒绝方案不允许审批")
    void approveRejectedProposalThrows() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);
        account.setId(1L);
        account.setProposalStatus(ManualResolutionWorkflow.PROPOSAL_REJECTED);

        when(suspenseAccountService.findSuspenseAccountById(1L))
                .thenReturn(Optional.of(account));

        assertThrows(IllegalStateException.class,
                () -> manualResolutionWorkflow.approveResolution(1L, "admin01"));
    }

    @Test
    @DisplayName("approveResolution — 不存在的 ID 抛出异常")
    void approveNotFoundThrows() {
        when(suspenseAccountService.findSuspenseAccountById(999L))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> manualResolutionWorkflow.approveResolution(999L, "admin01"));
    }

    // ==================== rejectResolution ====================

    @Test
    @DisplayName("rejectResolution — 审批拒绝后退回 PENDING 并记录原因")
    void rejectResolutionReturnsToPending() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);
        account.setId(1L);
        account.setProposal("Refund to customer");
        account.setProposedBy("operator01");
        account.setProposalStatus(ManualResolutionWorkflow.PROPOSAL_PENDING);

        when(suspenseAccountService.findSuspenseAccountById(1L))
                .thenReturn(Optional.of(account));
        when(suspenseAccountRepository.save(any(SuspenseAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SuspenseAccount result = manualResolutionWorkflow.rejectResolution(
                1L, "admin01", "Insufficient evidence");

        assertEquals(ManualResolutionWorkflow.PROPOSAL_REJECTED, result.getProposalStatus());
        assertEquals("admin01", result.getApprovedBy());
        assertEquals("Insufficient evidence", result.getRejectionReason());
        // 挂账状态保持 PENDING，允许重新提交方案
        assertEquals(SuspenseAccount.SuspenseStatus.PENDING, result.getStatus());
    }

    @Test
    @DisplayName("rejectResolution — 未提交方案不允许拒绝")
    void rejectWithoutProposalThrows() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);
        account.setId(1L);
        account.setProposalStatus(null);

        when(suspenseAccountService.findSuspenseAccountById(1L))
                .thenReturn(Optional.of(account));

        assertThrows(IllegalStateException.class,
                () -> manualResolutionWorkflow.rejectResolution(1L, "admin01", "reason"));
    }

    @Test
    @DisplayName("rejectResolution — 已通过方案不允许拒绝")
    void rejectApprovedProposalThrows() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);
        account.setId(1L);
        account.setProposalStatus(ManualResolutionWorkflow.PROPOSAL_APPROVED);

        when(suspenseAccountService.findSuspenseAccountById(1L))
                .thenReturn(Optional.of(account));

        assertThrows(IllegalStateException.class,
                () -> manualResolutionWorkflow.rejectResolution(1L, "admin01", "reason"));
    }

    @Test
    @DisplayName("rejectResolution — 不存在的 ID 抛出异常")
    void rejectNotFoundThrows() {
        when(suspenseAccountService.findSuspenseAccountById(999L))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> manualResolutionWorkflow.rejectResolution(999L, "admin01", "reason"));
    }

    // ==================== 辅助方法 ====================

    private SuspenseAccount createSuspenseAccount(SuspenseAccount.SuspenseStatus status) {
        SuspenseAccount account = new SuspenseAccount();
        account.setDiscrepancyId(DISCREPANCY_ID);
        account.setMerchantId(MERCHANT_ID);
        account.setAmount(new BigDecimal("100"));
        account.setDiscrepancyType(SuspenseAccount.DiscrepancyType.LONG_PAYMENT);
        account.setStatus(status);
        account.setCreatedAt(LocalDateTime.now());
        return account;
    }
}