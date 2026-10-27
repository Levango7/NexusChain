package org.nexus.walletsvc.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.entity.WithdrawalApproverEntity;
import org.nexus.walletsvc.entity.WithdrawalRequestEntity;
import org.nexus.walletsvc.repository.WithdrawalApproverRepository;
import org.nexus.walletsvc.repository.WithdrawalRequestRepository;
import org.nexus.walletsvc.repository.WhitelistEntryRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V7 防双花用例（打捞自 #280 的设计，板 20261027-1830）。
 *
 * <p>钉住的不变式：</p>
 * <ol>
 *   <li>审批曾尝试执行（{@code signingExecutionAttemptedAt} 非空）且 signing 侧又回到
 *       {@code APPROVED}（TTL 超时从 EXECUTING 回退）⇒ <b>判 EXECUTED_UNVERIFIED 且绝不再次
 *       executeApproved</b>——上次可能已广播只是响应丢失，重试即双花；</li>
 *   <li>signing 明确告知「本次未动账」（5000 审批未通过 / 5001 并发被抢）⇒ 清空标记，
 *       允许下次推进（否则一笔从未广播的提现会被永久判死）；</li>
 *   <li>结果不明（executeApproved 抛异常 / 响应形状异常）⇒ EXECUTED_UNVERIFIED，
 *       保留标记（不得压成 FAILED）；</li>
 *   <li>查审批状态抛异常（fallback 未覆盖的 Feign 错误）⇒ AWAITING_APPROVAL，
 *       不得判 FAILED（未知不能当确定，与 R1 同源）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawalDoubleSpendGuardTest {

    private static final String PLATFORM_WALLET = "0xplatform0000000000000000000000deadbeef";
    private static final String REQ_ID = "WD-doublespend-0001";
    private static final String SIGNING_APPROVAL_ID = "SAP-ds-0001";

    @Mock private SigningServiceFeignClient signingServiceClient;
    @Mock private WhitelistEntryRepository whitelistEntryRepository;
    @Mock private WithdrawalRequestRepository withdrawalRequestRepository;
    @Mock private WithdrawalApproverRepository withdrawalApproverRepository;

    private final Map<String, WithdrawalRequestEntity> requestStore = new ConcurrentHashMap<>();

    private DefaultWithdrawalApprovalService service;

    @BeforeEach
    void setUp() {
        requestStore.clear();
        when(withdrawalRequestRepository.findByRequestId(anyString())).thenAnswer(inv ->
                Optional.ofNullable(requestStore.get(inv.getArgument(0))));
        when(withdrawalRequestRepository.save(any(WithdrawalRequestEntity.class))).thenAnswer(inv -> {
            WithdrawalRequestEntity e = inv.getArgument(0);
            if (e.getCreatedAt() == null) {
                e.setCreatedAt(LocalDateTime.now());
            }
            if (e.getUpdatedAt() == null) {
                e.setUpdatedAt(LocalDateTime.now());
            }
            requestStore.put(e.getRequestId(), e);
            return e;
        });
        when(withdrawalRequestRepository.compareAndSetStatus(anyString(), any(), any())).thenAnswer(inv -> {
            String requestId = inv.getArgument(0);
            WithdrawalRequest.WithdrawalStatus from = inv.getArgument(1);
            WithdrawalRequest.WithdrawalStatus to = inv.getArgument(2);
            WithdrawalRequestEntity e = requestStore.get(requestId);
            if (e != null && e.getStatus() == from) {
                e.setStatus(to);
                return 1;
            }
            return 0;
        });
        when(withdrawalApproverRepository.findByRequestId(anyString()))
                .thenReturn(new ArrayList<>(Collections.emptyList()));

        DefaultApprovalPolicy policy = new DefaultApprovalPolicy(whitelistEntryRepository);
        service = new DefaultWithdrawalApprovalService(
                policy, signingServiceClient, PLATFORM_WALLET,
                withdrawalRequestRepository, withdrawalApproverRepository);
    }

    private WithdrawalRequestEntity seedAwaitingWithAttempt() {
        WithdrawalRequestEntity e = new WithdrawalRequestEntity();
        e.setRequestId(REQ_ID);
        e.setToAddress("0xrecipient000000000000");
        e.setAmount(new BigDecimal("50000"));
        e.setCurrency("NEX");
        e.setRequiredApprovers(2);
        e.setApprovedCount(2);
        e.setStatus(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL);
        e.setSigningApprovalId(SIGNING_APPROVAL_ID);
        // 关键前提：上一次推进已发起过 executeApproved（响应丢失/超时，结果不明）
        e.setSigningExecutionAttemptedAt(LocalDateTime.now().minusMinutes(3));
        requestStore.put(REQ_ID, e);
        return e;
    }

    private static Map<String, Object> envelope(int statusCode, Object data, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("statusCode", statusCode);
        if (data != null) {
            m.put("data", data);
        }
        if (message != null) {
            m.put("message", message);
        }
        return m;
    }

    private static Map<String, Object> approvalStatusEnvelope(String status) {
        Map<String, Object> data = new HashMap<>();
        data.put("requestId", SIGNING_APPROVAL_ID);
        data.put("status", status);
        return envelope(2000, data, null);
    }

    // ==================== 防双花：曾尝试执行 ⇒ 绝不重试 ====================

    @Test
    @DisplayName("曾尝试执行 + signing 回退到 APPROVED：EXECUTED_UNVERIFIED，绝不再次 executeApproved")
    void resume_afterPriorAttempt_doesNotRetry() {
        WithdrawalRequestEntity e = seedAwaitingWithAttempt();
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("APPROVED"));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus(),
                "上次可能已广播，重试即双花；判 EXECUTED_UNVERIFIED 交人工核对");
        assertEquals("signing approval returned to APPROVED after a prior execution attempt;"
                        + " possible prior broadcast, manual reconciliation required",
                e.getRejectionReason());
        verify(signingServiceClient, never()).executeApproved(anyString());
    }

    @Test
    @DisplayName("未尝试执行 + APPROVED：正常 executeApproved 并落 attempted 标记")
    void resume_firstAttempt_executesAndMarks() {
        WithdrawalRequestEntity e = seedAwaitingWithAttempt();
        e.setSigningExecutionAttemptedAt(null);
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("APPROVED"));
        when(signingServiceClient.executeApproved(SIGNING_APPROVAL_ID))
                .thenReturn(envelope(2000, "0xhash-ccc", null));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, e.getStatus());
        assertEquals("0xhash-ccc", e.getChainTxHash());
        assertNotNull(e.getSigningExecutionAttemptedAt(),
                "执行前必须落 attempted 标记，下次推进才有防双花依据");
    }

    // ==================== 确定未动账：清空标记 ====================

    @Test
    @DisplayName("executeApproved 返回 5000（审批未通过）：清空 attempted，允许下次推进")
    void resume_definitelyNotExecuted_5000_clearsAttempted() {
        WithdrawalRequestEntity e = seedAwaitingWithAttempt();
        e.setSigningExecutionAttemptedAt(null);
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("APPROVED"));
        when(signingServiceClient.executeApproved(SIGNING_APPROVAL_ID))
                .thenReturn(envelope(5000, null, "approval rejected by signers"));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL, e.getStatus(),
                "5000 = 本次确定未动账，保持等待留待人工再推进");
        assertNull(e.getSigningExecutionAttemptedAt(),
                "确定未动账必须清标记：否则下次推进被误判成「可能已广播」而永久判死");
    }

    @Test
    @DisplayName("executeApproved 返回 5001（并发被抢）：清空 attempted")
    void resume_definitelyNotExecuted_5001_clearsAttempted() {
        WithdrawalRequestEntity e = seedAwaitingWithAttempt();
        e.setSigningExecutionAttemptedAt(null);
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("APPROVED"));
        when(signingServiceClient.executeApproved(SIGNING_APPROVAL_ID))
                .thenReturn(envelope(5001, null, "execution taken by another caller"));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL, e.getStatus());
        assertNull(e.getSigningExecutionAttemptedAt());
    }

    // ==================== 结果不明：不判 FAILED、保留标记 ====================

    @Test
    @DisplayName("executeApproved 抛异常：EXECUTED_UNVERIFIED 且保留 attempted（请求已发出）")
    void resume_executeApprovedThrows_executedUnverifiedKeepsAttempted() {
        WithdrawalRequestEntity e = seedAwaitingWithAttempt();
        e.setSigningExecutionAttemptedAt(null);
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("APPROVED"));
        when(signingServiceClient.executeApproved(SIGNING_APPROVAL_ID))
                .thenThrow(new RuntimeException("feign decode error"));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus(),
                "请求已发出但结果不明：不得压成 FAILED，否则下一步可能被当「没出」清账再放款");
        assertNotNull(e.getSigningExecutionAttemptedAt());
    }

    @Test
    @DisplayName("executeApproved 响应形状异常（2000 但无 data）：EXECUTED_UNVERIFIED 保留 attempted")
    void resume_outcomeUnknown_keepsAttempted() {
        WithdrawalRequestEntity e = seedAwaitingWithAttempt();
        e.setSigningExecutionAttemptedAt(null);
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("APPROVED"));
        when(signingServiceClient.executeApproved(SIGNING_APPROVAL_ID))
                .thenReturn(envelope(2000, null, null));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus());
        assertNotNull(e.getSigningExecutionAttemptedAt(),
                "结果不明必须保留标记：写回可重试语义会在下次推进时双花");
    }

    // ==================== 查状态失败：不判 FAILED ====================

    @Test
    @DisplayName("getApprovalStatus 抛异常：AWAITING_APPROVAL（查询失败≠钱动了，与 R1 同源）")
    void resume_statusQueryThrows_staysAwaiting() {
        WithdrawalRequestEntity e = seedAwaitingWithAttempt();
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenThrow(new RuntimeException("connect timeout"));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL, e.getStatus(),
                "查询失败不代表钱动了；判 FAILED 会把本可继续等待的提现判死");
        verify(signingServiceClient, never()).executeApproved(anyString());
    }
}
