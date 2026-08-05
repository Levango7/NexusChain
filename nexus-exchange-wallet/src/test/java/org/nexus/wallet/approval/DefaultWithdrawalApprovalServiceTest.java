package org.nexus.wallet.approval;

import org.junit.Before;
import org.junit.Test;

import java.math.BigDecimal;

import static org.junit.Assert.*;

/**
 * {@link DefaultWithdrawalApprovalService} 单元测试：验证多审批人提币工作流
 * （请求 → 审批累计 → 达到阈值转 APPROVED → 执行）。
 */
public class DefaultWithdrawalApprovalServiceTest {

    private DefaultApprovalPolicy policy;
    private DefaultWithdrawalApprovalService service;

    @Before
    public void setUp() {
        policy = new DefaultApprovalPolicy();
        policy.addToWhitelist("0xwhitelisted");
        service = new DefaultWithdrawalApprovalService(policy);
    }

    @Test
    public void testRequestWithdrawal_smallAmountNeedsOneApprover() {
        WithdrawalRequest request = service.requestWithdrawal(
                "0xwhitelisted", new BigDecimal("5000"), "NEX");

        assertNotNull(request.getRequestId());
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, request.getStatus());
        assertEquals(Integer.valueOf(1), request.getRequiredApprovers());
    }

    @Test
    public void testRequestWithdrawal_largeAmountNeedsThreeApprovers() {
        WithdrawalRequest request = service.requestWithdrawal(
                "0xwhitelisted", new BigDecimal("500000"), "NEX");

        assertEquals(Integer.valueOf(3), request.getRequiredApprovers());
    }

    @Test(expected = IllegalStateException.class)
    public void testRequestWithdrawal_nonWhitelistedRejected() {
        service.requestWithdrawal("0xunknown", new BigDecimal("100"), "NEX");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testRequestWithdrawal_nonPositiveAmountRejected() {
        service.requestWithdrawal("0xwhitelisted", BigDecimal.ZERO, "NEX");
    }

    @Test
    public void testApprove_reachesThresholdTransitionsToApproved() {
        WithdrawalRequest request = service.requestWithdrawal(
                "0xwhitelisted", new BigDecimal("50000"), "NEX"); // 需 2 人
        assertEquals(Integer.valueOf(2), request.getRequiredApprovers());

        service.approve(request.getRequestId(), "approver-1");
        assertEquals(WithdrawalRequest.WithdrawalStatus.PENDING, request.getStatus());

        WithdrawalRequest approved = service.approve(request.getRequestId(), "approver-2");
        assertEquals(WithdrawalRequest.WithdrawalStatus.APPROVED, approved.getStatus());
        assertEquals(Integer.valueOf(2), approved.getApprovedCount());
    }

    @Test(expected = IllegalStateException.class)
    public void testApprove_duplicateApproverRejected() {
        WithdrawalRequest request = service.requestWithdrawal(
                "0xwhitelisted", new BigDecimal("50000"), "NEX");
        service.approve(request.getRequestId(), "approver-1");
        service.approve(request.getRequestId(), "approver-1"); // 重复
    }

    @Test(expected = IllegalStateException.class)
    public void testApprove_nonPendingRejected() {
        WithdrawalRequest request = service.requestWithdrawal(
                "0xwhitelisted", new BigDecimal("100"), "NEX"); // 需 1 人
        service.approve(request.getRequestId(), "approver-1"); // 转 APPROVED
        service.approve(request.getRequestId(), "approver-2"); // 非 PENDING
    }

    @Test
    public void testReject_setsRejectedWithReason() {
        WithdrawalRequest request = service.requestWithdrawal(
                "0xwhitelisted", new BigDecimal("100"), "NEX");

        WithdrawalRequest rejected = service.reject(request.getRequestId(), "approver-1", "suspicious");

        assertEquals(WithdrawalRequest.WithdrawalStatus.REJECTED, rejected.getStatus());
        assertEquals("suspicious", rejected.getRejectionReason());
    }

    @Test
    public void testExecuteApprovedWithdrawal_setsExecutedWithTxHash() {
        WithdrawalRequest request = service.requestWithdrawal(
                "0xwhitelisted", new BigDecimal("100"), "NEX");
        service.approve(request.getRequestId(), "approver-1");

        WithdrawalRequest executed = service.executeApprovedWithdrawal(request.getRequestId());

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, executed.getStatus());
        assertNotNull(executed.getChainTxHash());
        assertTrue(executed.getChainTxHash().startsWith("SIMULATED-"));
        assertNotNull(executed.getExecutedAt());
    }

    @Test(expected = IllegalStateException.class)
    public void testExecute_pendingRequestRejected() {
        WithdrawalRequest request = service.requestWithdrawal(
                "0xwhitelisted", new BigDecimal("100"), "NEX");
        service.executeApprovedWithdrawal(request.getRequestId()); // 未审批
    }
}
