package org.nexus.gateway.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.approval.ApprovalExecutionLink;
import org.nexus.gateway.approval.SigningApprovalExecutionService;
import org.nexus.gateway.approval.SigningOutcome;
import org.nexus.settlement.clearing.ClearingEngine;
import org.nexus.settlement.clearing.ClearingOrder;
import org.nexus.settlement.clearing.ClearingOrderRepository;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SettlementApprovalReconciler} 单元测试。
 *
 * <p>两条硬约束：①<b>只扫 {@code AWAITING}</b>（扫 PENDING 会把单子重跑 {@code settle()}，
 * 而 {@code bookSettlement} 无去重 ⇒ 重复贷账）；②按审批结果一次性收口，
 * 仍在审批中/执行中不得改写业务状态。</p>
 */
class SettlementApprovalReconcilerTest {

    private ClearingOrderRepository repository;
    private ClearingEngine clearingEngine;
    private SigningApprovalExecutionService signingService;
    private SettlementApprovalReconciler reconciler;

    @BeforeEach
    void setUp() {
        repository = mock(ClearingOrderRepository.class);
        clearingEngine = mock(ClearingEngine.class);
        signingService = mock(SigningApprovalExecutionService.class);
        reconciler = new SettlementApprovalReconciler(repository, clearingEngine, signingService);
    }

    private static ClearingOrder awaiting(String orderId) {
        ClearingOrder order = new ClearingOrder();
        order.setOrderId(orderId);
        order.setMerchantId("M001");
        order.setAmount(new BigDecimal("100"));
        order.setCurrency("NEX");
        order.setStatus(ClearingOrder.OrderStatus.AWAITING);
        return order;
    }

    private void stubCandidates(ClearingOrder... orders) {
        when(repository.findTop100ByStatusOrderByOrderIdAsc(ClearingOrder.OrderStatus.AWAITING))
                .thenReturn(List.of(orders));
    }

    @Test
    @DisplayName("只扫 AWAITING：不扫 PENDING、不走全表 findByStatus")
    void scansOnlyAwaiting() {
        stubCandidates();
        reconciler.advanceAwaitingApprovals();

        verify(repository).findTop100ByStatusOrderByOrderIdAsc(ClearingOrder.OrderStatus.AWAITING);
        verify(repository, never()).findTop100ByStatusOrderByOrderIdAsc(ClearingOrder.OrderStatus.PENDING);
        verify(repository, never()).findByStatus(any());
    }

    @Test
    @DisplayName("审批已执行→按 execution:SETTLEMENT:<orderId> 推进并收口 SETTLED")
    void executedOutcomeCompletesSettlement() {
        stubCandidates(awaiting("clr_1"));
        when(signingService.refresh("execution:SETTLEMENT:clr_1")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED, null, "0xSettle", null));

        reconciler.advanceAwaitingApprovals();

        verify(clearingEngine).completeAfterSigningApproval("clr_1", "0xSettle");
        verify(clearingEngine, never()).markSigningApprovalRejected(anyString(), anyString());
    }

    @Test
    @DisplayName("审批被拒/过期→FAILED（只有确定未动账才允许记失败）")
    void failedOutcomeMarksRejected() {
        stubCandidates(awaiting("clr_2"));
        when(signingService.refresh("execution:SETTLEMENT:clr_2")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.FAILED, "appr-2", null, "审批被拒"));

        reconciler.advanceAwaitingApprovals();

        verify(clearingEngine).markSigningApprovalRejected(eq("clr_2"), eq("审批被拒"));
        verify(clearingEngine, never()).completeAfterSigningApproval(anyString(), anyString());
    }

    @Test
    @DisplayName("结果不可核对→UNVERIFIED（禁止自动重试）")
    void unverifiedOutcomeMarksUnverified() {
        stubCandidates(awaiting("clr_3"));
        when(signingService.refresh("execution:SETTLEMENT:clr_3")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.EXECUTION_UNKNOWN,
                        "appr-3", null, "结果未知"));

        reconciler.advanceAwaitingApprovals();

        verify(clearingEngine).markSigningApprovalUnverified(eq("clr_3"), anyString());
    }

    @Test
    @DisplayName("仍在审批/执行中→不改写业务状态，留待下一轮")
    void stillAwaitingLeavesBusinessUntouched() {
        stubCandidates(awaiting("clr_4"));
        when(signingService.refresh("execution:SETTLEMENT:clr_4")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.AWAITING_APPROVAL,
                        "appr-4", null, null));

        reconciler.advanceAwaitingApprovals();

        verify(clearingEngine, never()).completeAfterSigningApproval(anyString(), anyString());
        verify(clearingEngine, never()).markSigningApprovalRejected(anyString(), anyString());
        verify(clearingEngine, never()).markSigningApprovalUnverified(anyString(), anyString());
    }

    @Test
    @DisplayName("单笔抛异常不影响同批其他清算单")
    void oneFailureDoesNotAbortBatch() {
        stubCandidates(awaiting("clr_5"), awaiting("clr_6"));
        when(signingService.refresh("execution:SETTLEMENT:clr_5"))
                .thenThrow(new RuntimeException("db down"));
        when(signingService.refresh("execution:SETTLEMENT:clr_6")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED, null, "0xSix", null));

        reconciler.advanceAwaitingApprovals();

        verify(clearingEngine).completeAfterSigningApproval("clr_6", "0xSix");
    }

    @Test
    @DisplayName("收口被引擎拒绝（状态已被他方改写）时不得误记 FAILED")
    void completionRefusedDoesNotMarkRejected() {
        stubCandidates(awaiting("clr_7"));
        when(signingService.refresh("execution:SETTLEMENT:clr_7")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED, null, "  ", null));
        when(clearingEngine.completeAfterSigningApproval("clr_7", "  ")).thenReturn(false);

        reconciler.advanceAwaitingApprovals();

        verify(clearingEngine, never()).markSigningApprovalRejected(anyString(), anyString());
        verify(clearingEngine, never()).markSigningApprovalUnverified(anyString(), anyString());
    }
}
