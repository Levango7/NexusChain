package org.nexus.gateway.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SigningApprovalExecutionService} 状态机单元测试。
 *
 * <p>重点是资金安全不变量：</p>
 * <ul>
 *   <li>2001 必须落审批链接并返回待审批，不能当失败；</li>
 *   <li>APPROVED 才执行，执行前先落 EXECUTING 标记；</li>
 *   <li>执行结果不明（null/5001）后即便签名状态回退成 APPROVED，也<b>不得再次执行</b>；</li>
 *   <li>签名报告 EXECUTED 但调用方无 txHash ⇒ 人工核对，不得当作成功或失败。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SigningApprovalExecutionServiceTest {

    private static final String KEY = "execution:REFUND:refund:42";
    private static final String APPROVAL_ID = "ap-1";
    private static final String TX_HASH = "0xabc";

    @Mock
    private SigningServiceFeignClient signingClient;

    @Mock
    private ApprovalExecutionLinkRepository repository;

    private SigningApprovalExecutionService service;

    @BeforeEach
    void setUp() {
        service = new SigningApprovalExecutionService(signingClient, repository,
                Duration.ofHours(2), Duration.ofHours(1));
        org.mockito.Mockito.lenient()
                .when(repository.save(any(ApprovalExecutionLink.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // === 首次调用 signTransfer ===

    @Test
    @DisplayName("signTransfer 2000 -> EXECUTED，不落链接")
    void signOrAwait_directSuccess() {
        when(signingClient.signTransfer("pk", "to-hash", BigDecimal.TEN))
                .thenReturn(Map.of("statusCode", 2000, "data", TX_HASH));

        SigningOutcome outcome = service.signOrAwait(KEY, "REFUND", "pk", "to-hash",
                BigDecimal.TEN, "NEX");

        assertTrue(outcome.isExecuted());
        assertEquals(TX_HASH, outcome.txHash());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("signTransfer 2001 -> 落 AWAITING_APPROVAL 链接并返回 approvalId")
    void signOrAwait_persistsAwaitingLink() {
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.empty());
        when(signingClient.signTransfer("pk", "to-hash", BigDecimal.TEN))
                .thenReturn(Map.of("statusCode", 2001, "status", "PENDING", "approvalId", APPROVAL_ID));

        SigningOutcome outcome = service.signOrAwait(KEY, "REFUND", "pk", "to-hash",
                BigDecimal.TEN, "NEX");

        assertTrue(outcome.isAwaiting());
        assertEquals(APPROVAL_ID, outcome.approvalId());
        ArgumentCaptor<ApprovalExecutionLink> captor =
                ArgumentCaptor.forClass(ApprovalExecutionLink.class);
        verify(repository).save(captor.capture());
        ApprovalExecutionLink saved = captor.getValue();
        assertEquals(ApprovalExecutionLink.Status.AWAITING_APPROVAL, saved.getStatus());
        assertEquals(KEY, saved.getRequestKey());
        assertEquals(APPROVAL_ID, saved.getApprovalId());
        assertEquals(new BigDecimal("10"), saved.getAmount());
        assertNull(saved.getExecutionAttemptedAt());
    }

    @Test
    @DisplayName("2001 缺 approvalId -> FAILED，不落链接（无法跟踪的审批禁止当成功）")
    void signOrAwait_approvalWithoutId() {
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.empty());
        when(signingClient.signTransfer(any(), any(), any()))
                .thenReturn(Map.of("statusCode", 2001, "status", "PENDING"));

        SigningOutcome outcome = service.signOrAwait(KEY, "REFUND", "pk", "to-hash",
                BigDecimal.TEN, "NEX");

        assertTrue(outcome.isFailed());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("signTransfer 降级/抛异常 -> FAILED（确定未动账）")
    void signOrAwait_unavailable() {
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.empty());
        when(signingClient.signTransfer(any(), any(), any())).thenReturn(null);
        assertTrue(service.signOrAwait(KEY, "REFUND", "pk", "to-hash", BigDecimal.TEN, "NEX").isFailed());

        when(signingClient.signTransfer(any(), any(), any())).thenThrow(new RuntimeException("down"));
        assertTrue(service.signOrAwait(KEY, "REFUND", "pk", "to-hash", BigDecimal.TEN, "NEX").isFailed());
    }

    @Test
    @DisplayName("同一 requestKey 第二次调用走既有链接，不再次 signTransfer")
    void signOrAwait_existingLink_doesNotResign() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.AWAITING_APPROVAL, null, null);
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("PENDING", false));

        SigningOutcome outcome = service.signOrAwait(KEY, "REFUND", "pk", "to-hash",
                BigDecimal.TEN, "NEX");

        assertTrue(outcome.isAwaiting());
        verify(signingClient, never()).signTransfer(any(), any(), any());
    }

    // === 审批推进 ===

    @Test
    @DisplayName("PENDING -> 保持 AWAITING_APPROVAL")
    void advance_pending() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.AWAITING_APPROVAL, null, null);
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("PENDING", false));

        SigningOutcome outcome = service.refresh(KEY);

        assertEquals(ApprovalExecutionLink.Status.AWAITING_APPROVAL, outcome.status());
        verify(signingClient, never()).executeApproved(anyString());
    }

    @Test
    @DisplayName("PENDING + expired=true -> FAILED（确定未动账）")
    void advance_pendingExpired() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.AWAITING_APPROVAL, null, null);
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("PENDING", true));

        assertTrue(service.refresh(KEY).isFailed());
        verify(signingClient, never()).executeApproved(anyString());
    }

    @Test
    @DisplayName("APPROVED -> 先落 EXECUTING 再执行；2000+hash -> EXECUTED 且保存哈希")
    void advance_approved_executes() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.AWAITING_APPROVAL, null, null);
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("APPROVED", false));
        when(signingClient.executeApproved(APPROVAL_ID))
                .thenReturn(Map.of("statusCode", 2000, "data", TX_HASH));

        SigningOutcome outcome = service.refresh(KEY);

        assertTrue(outcome.isExecuted());
        assertEquals(TX_HASH, outcome.txHash());
        assertEquals(ApprovalExecutionLink.Status.EXECUTED, existing.getStatus());
        assertEquals(TX_HASH, existing.getTxHash());
        assertTrue(existing.getAttempts() >= 1);
        assertTrue(existing.getExecutionAttemptedAt() != null);
    }

    @Test
    @DisplayName("executeApproved 明确 5000 -> 回退 APPROVED（未动账，可安全重试）")
    void advance_executeDeclaredFailure_isRetryable() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.AWAITING_APPROVAL, null, null);
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("APPROVED", false));
        when(signingClient.executeApproved(APPROVAL_ID))
                .thenReturn(Map.of("statusCode", 5000, "message", "签名失败"));

        SigningOutcome outcome = service.refresh(KEY);

        assertEquals(ApprovalExecutionLink.Status.APPROVED, outcome.status());
        assertEquals("签名失败", outcome.error());
        assertNull(existing.getExecutionAttemptedAt());
    }

    @Test
    @DisplayName("executeApproved 降级(null) -> EXECUTING（结果未知，不判失败）")
    void advance_executeUnknown_staysExecuting() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.AWAITING_APPROVAL, null, null);
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("APPROVED", false));
        when(signingClient.executeApproved(APPROVAL_ID)).thenReturn(null);

        SigningOutcome outcome = service.refresh(KEY);

        assertEquals(ApprovalExecutionLink.Status.EXECUTING, outcome.status());
        assertFalse(outcome.isFailed());
    }

    @Test
    @DisplayName("关键：曾发起执行后签名状态回退 APPROVED -> EXECUTION_UNKNOWN，绝不再执行")
    void advance_revertAfterAttempt_neverReexecutes() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.EXECUTING, null,
                LocalDateTime.now().minusMinutes(30));
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("APPROVED", false));

        SigningOutcome outcome = service.refresh(KEY);

        assertEquals(ApprovalExecutionLink.Status.EXECUTION_UNKNOWN, outcome.status());
        assertTrue(outcome.requiresManualVerification());
        verify(signingClient, never()).executeApproved(anyString());
    }

    @Test
    @DisplayName("EXECUTING 结果轮询到 EXECUTED -> 有哈希则 EXECUTED")
    void advance_executingThenExecuted_withHash() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.EXECUTING, TX_HASH,
                LocalDateTime.now().minusMinutes(1));
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("EXECUTED", false));

        SigningOutcome outcome = service.refresh(KEY);

        assertEquals(ApprovalExecutionLink.Status.EXECUTED, outcome.status());
        assertEquals(TX_HASH, outcome.txHash());
        verify(signingClient, never()).executeApproved(anyString());
    }

    @Test
    @DisplayName("签名报告 EXECUTED 但无哈希 -> EXECUTED_UNVERIFIED（人工核对，禁自动重试）")
    void advance_executedWithoutHash_needsManualVerification() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.EXECUTING, null,
                LocalDateTime.now().minusMinutes(1));
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("EXECUTED", false));

        SigningOutcome outcome = service.refresh(KEY);

        assertEquals(ApprovalExecutionLink.Status.EXECUTED_UNVERIFIED, outcome.status());
        assertTrue(outcome.requiresManualVerification());
        verify(signingClient, never()).executeApproved(anyString());
    }

    @Test
    @DisplayName("EXECUTING 卡死超阈值 -> EXECUTION_UNKNOWN（停止自动推进）")
    void advance_executingStale_goesManual() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.EXECUTING, null,
                LocalDateTime.now().minusHours(2));
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("EXECUTING", false));

        SigningOutcome outcome = service.refresh(KEY);

        assertEquals(ApprovalExecutionLink.Status.EXECUTION_UNKNOWN, outcome.status());
    }

    @Test
    @DisplayName("REJECTED -> FAILED（未发起过执行时）")
    void advance_rejected_fails() {
        when(repository.findByRequestKey(KEY))
                .thenReturn(Optional.of(link(ApprovalExecutionLink.Status.AWAITING_APPROVAL, null, null)));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("REJECTED", false));

        assertEquals(ApprovalExecutionLink.Status.FAILED, service.refresh(KEY).status());
        verify(signingClient, never()).executeApproved(anyString());
    }

    @Test
    @DisplayName("EXPIRED -> FAILED（未发起过执行时）")
    void advance_expired_fails() {
        when(repository.findByRequestKey(KEY))
                .thenReturn(Optional.of(link(ApprovalExecutionLink.Status.AWAITING_APPROVAL, null, null)));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("EXPIRED", false));

        assertEquals(ApprovalExecutionLink.Status.FAILED, service.refresh(KEY).status());
        verify(signingClient, never()).executeApproved(anyString());
    }

    @Test
    @DisplayName("查询降级 + 等待超时 -> FAILED（不永久挂起）")
    void advance_statusUnavailable_pastTimeout() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.AWAITING_APPROVAL, null, null);
        existing.setCreatedAt(LocalDateTime.now().minusHours(3));
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(null);

        assertTrue(service.refresh(KEY).isFailed());
    }

    @Test
    @DisplayName("终态链接不再触发任何远端调用")
    void advance_terminal_noRemoteCalls() {
        ApprovalExecutionLink existing = link(ApprovalExecutionLink.Status.EXECUTED, TX_HASH, null);
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.of(existing));

        SigningOutcome outcome = service.refresh(KEY);

        assertTrue(outcome.isExecuted());
        verifyNoInteractions(signingClient);
    }

    @Test
    @DisplayName("refresh 未知 requestKey -> FAILED（不得当成功）")
    void refresh_unknownKey() {
        when(repository.findByRequestKey(KEY)).thenReturn(Optional.empty());
        assertTrue(service.refresh(KEY).isFailed());
    }

    @Test
    @DisplayName("requestKey 缺失 -> IllegalArgumentException")
    void signOrAwait_requiresKey() {
        assertThrows(IllegalArgumentException.class,
                () -> service.signOrAwait(" ", "REFUND", "pk", "to-hash", BigDecimal.TEN, "NEX"));
    }

    @Test
    @DisplayName("并发保存冲突时采用库中最新链接，不再重复签名")
    void signOrAwait_raceFallsBackToWinner() {
        ApprovalExecutionLink winner = link(ApprovalExecutionLink.Status.AWAITING_APPROVAL, null, null);
        when(repository.findByRequestKey(KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(signingClient.signTransfer(any(), any(), any()))
                .thenReturn(Map.of("statusCode", 2001, "approvalId", APPROVAL_ID));
        java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();
        when(repository.save(any(ApprovalExecutionLink.class))).thenAnswer(invocation -> {
            if (saves.getAndIncrement() == 0) {
                throw new org.springframework.dao.DataIntegrityViolationException("dup");
            }
            return invocation.getArgument(0);
        });
        when(signingClient.getApprovalStatus(APPROVAL_ID)).thenReturn(status("PENDING", false));

        SigningOutcome outcome = service.signOrAwait(KEY, "REFUND", "pk", "to-hash",
                BigDecimal.TEN, "NEX");

        assertTrue(outcome.isAwaiting());
    }

    // --- helpers ---

    private static Map<String, Object> status(String status, boolean expired) {
        return Map.of("statusCode", 2000,
                "data", Map.of("requestId", APPROVAL_ID, "status", status, "expired", expired));
    }

    private static ApprovalExecutionLink link(ApprovalExecutionLink.Status status, String txHash,
                                              LocalDateTime executionAttemptedAt) {
        ApprovalExecutionLink link = new ApprovalExecutionLink();
        link.setRequestKey(KEY);
        link.setRequestType("REFUND");
        link.setApprovalId(APPROVAL_ID);
        link.setFromPubkey("pk");
        link.setToPubkeyHash("to-hash");
        link.setAmount(BigDecimal.TEN);
        link.setCurrency("NEX");
        link.setStatus(status);
        link.setTxHash(txHash);
        link.setExecutionAttemptedAt(executionAttemptedAt);
        link.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        link.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return link;
    }
}
