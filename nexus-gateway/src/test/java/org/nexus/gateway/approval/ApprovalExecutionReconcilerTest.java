package org.nexus.gateway.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.gateway.model.Subscription;
import org.nexus.gateway.repository.SubscriptionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ApprovalExecutionReconciler} 单元测试：白名单范围、审批通过后的执行与业务回写、
 * 以及"结果不可核对不得重试"的护栏。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApprovalExecutionReconcilerTest {

    @Mock private ApprovalExecutionLinkRepository linkRepository;
    @Mock private SigningApprovalExecutionService signingApprovalExecutionService;
    @Mock private SubscriptionRepository subscriptionRepository;

    private ApprovalExecutionReconciler reconciler;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        lenient().when(tm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        reconciler = new ApprovalExecutionReconciler(linkRepository,
                signingApprovalExecutionService, subscriptionRepository, tm);
    }

    private static ApprovalExecutionLink link(String requestType, String requestKey,
                                              ApprovalExecutionLink.Status status) {
        ApprovalExecutionLink link = new ApprovalExecutionLink();
        link.setRequestType(requestType);
        link.setRequestKey(requestKey);
        link.setApprovalId("appr-" + requestKey.hashCode());
        link.setStatus(status);
        link.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        return link;
    }

    private void stubCandidates(ApprovalExecutionLink... links) {
        when(linkRepository.findTop100ByRequestTypeInAndStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                anyCollection(), anyCollection(), any())).thenReturn(List.of(links));
    }

    @Test
    @DisplayName("白名单只含无轮询调用方的类型：CHAIN/CONSORTIUM 支付不得被后台执行")
    void managedTypesExcludePolledPayments() {
        assertTrue(ApprovalExecutionReconciler.MANAGED_REQUEST_TYPES.contains("SUB_CHARGE"));
        assertTrue(ApprovalExecutionReconciler.MANAGED_REQUEST_TYPES.contains("SUB_AUTH"));
        assertTrue(ApprovalExecutionReconciler.MANAGED_REQUEST_TYPES.contains("CHAIN_REFUND"));
        assertTrue(ApprovalExecutionReconciler.MANAGED_REQUEST_TYPES.contains("CONSORTIUM_REFUND"));
        assertFalse(ApprovalExecutionReconciler.MANAGED_REQUEST_TYPES.contains("CHAIN"),
                "由该笔支付自己的状态轮询推进，后台再扫会造成双花面");
        assertFalse(ApprovalExecutionReconciler.MANAGED_REQUEST_TYPES.contains("CONSORTIUM"));

        stubCandidates();
        reconciler.advanceAwaitingApprovals();

        ArgumentCaptor<Collection<String>> types = ArgumentCaptor.forClass(Collection.class);
        verify(linkRepository).findTop100ByRequestTypeInAndStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                types.capture(), anyCollection(), any());
        assertEquals(ApprovalExecutionReconciler.MANAGED_REQUEST_TYPES, List.copyOf(types.getValue()));
    }

    @Test
    @DisplayName("SUB_AUTH 审批通过并执行→补齐订阅 authTxHash")
    void executedSubscriptionAuthBackfillsTxHash() {
        ApprovalExecutionLink link = link("SUB_AUTH", "execution:SUB_AUTH:SUB1",
                ApprovalExecutionLink.Status.AWAITING_APPROVAL);
        stubCandidates(link);
        when(signingApprovalExecutionService.refresh("execution:SUB_AUTH:SUB1")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED, null, "0xAuth", null));

        Subscription sub = new Subscription();
        sub.setSubscriptionNo("SUB1");
        sub.setAuthTxHash(null);
        when(subscriptionRepository.findBySubscriptionNo("SUB1")).thenReturn(Optional.of(sub));
        when(subscriptionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        reconciler.advanceAwaitingApprovals();

        assertEquals("0xAuth", sub.getAuthTxHash());
    }

    @Test
    @DisplayName("已有 authTxHash 时不覆盖（幂等）")
    void executedSubscriptionAuthDoesNotOverwriteExistingHash() {
        ApprovalExecutionLink link = link("SUB_AUTH", "execution:SUB_AUTH:SUB2",
                ApprovalExecutionLink.Status.APPROVED);
        stubCandidates(link);
        when(signingApprovalExecutionService.refresh("execution:SUB_AUTH:SUB2")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED, null, "0xNew", null));

        Subscription sub = new Subscription();
        sub.setSubscriptionNo("SUB2");
        sub.setAuthTxHash("0xExisting");
        when(subscriptionRepository.findBySubscriptionNo("SUB2")).thenReturn(Optional.of(sub));

        reconciler.advanceAwaitingApprovals();

        assertEquals("0xExisting", sub.getAuthTxHash());
        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("SUB_CHARGE 执行完成无需回写业务表（认领时计数已推进）")
    void executedSubscriptionChargeWritesNothing() {
        ApprovalExecutionLink link = link("SUB_CHARGE", "execution:SUB_CHARGE:SUB3:1",
                ApprovalExecutionLink.Status.AWAITING_APPROVAL);
        stubCandidates(link);
        when(signingApprovalExecutionService.refresh("execution:SUB_CHARGE:SUB3:1")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED, null, "0xCharge", null));

        reconciler.advanceAwaitingApprovals();

        verify(subscriptionRepository, never()).save(any());
        verify(subscriptionRepository, never()).findBySubscriptionNo(any());
    }

    @Test
    @DisplayName("结果不可核对→不回写、不重试，只留人工核对日志")
    void unverifiedOutcomeDoesNotTouchBusiness() {
        ApprovalExecutionLink link = link("SUB_CHARGE", "execution:SUB_CHARGE:SUB4:1",
                ApprovalExecutionLink.Status.EXECUTION_UNKNOWN);
        stubCandidates(link);
        when(signingApprovalExecutionService.refresh("execution:SUB_CHARGE:SUB4:1")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.EXECUTION_UNKNOWN,
                        "appr-x", null, "结果未知"));

        reconciler.advanceAwaitingApprovals();

        verify(subscriptionRepository, never()).save(any());
    }

    @Test
    @DisplayName("单笔抛异常不影响同批其他链接")
    void oneFailureDoesNotAbortBatch() {
        ApprovalExecutionLink bad = link("CHAIN_REFUND", "execution:CHAIN_REFUND:c1:100",
                ApprovalExecutionLink.Status.AWAITING_APPROVAL);
        ApprovalExecutionLink good = link("CHAIN_REFUND", "execution:CHAIN_REFUND:c2:100",
                ApprovalExecutionLink.Status.AWAITING_APPROVAL);
        stubCandidates(bad, good);
        when(signingApprovalExecutionService.refresh("execution:CHAIN_REFUND:c1:100"))
                .thenThrow(new RuntimeException("db down"));
        when(signingApprovalExecutionService.refresh("execution:CHAIN_REFUND:c2:100")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED, null, "0xRefund", null));

        reconciler.advanceAwaitingApprovals();

        verify(signingApprovalExecutionService).refresh("execution:CHAIN_REFUND:c2:100");
    }

    @Test
    @DisplayName("EXECUTED 但缺哈希→不回写（保守，交人工核对）")
    void executedWithoutHashIsNotBackfilled() {
        ApprovalExecutionLink link = link("SUB_AUTH", "execution:SUB_AUTH:SUB5",
                ApprovalExecutionLink.Status.EXECUTED_UNVERIFIED);
        stubCandidates(link);
        when(signingApprovalExecutionService.refresh("execution:SUB_AUTH:SUB5")).thenReturn(
                new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED, null, "  ", null));
        when(subscriptionRepository.findBySubscriptionNo(eq("SUB5"))).thenReturn(Optional.empty());

        reconciler.advanceAwaitingApprovals();

        verify(subscriptionRepository, never()).save(any());
    }
}
