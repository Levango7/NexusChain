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
import org.nexus.sdk.wallet.WalletUtils;
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
import java.util.List;
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
 * (c)-lite 用例：提现执行腿的 signing 审批四态语义与**复用不新建**不变式
 * （板 20261025-0415 §五 / 20261027-0306，(b2) 前提被推翻后用户拍板的止血路径）。
 *
 * <p>钉住的不变式：</p>
 * <ol>
 *   <li>首次执行遇 signing 2001 ⇒ 持久化 approvalId + 状态 AWAITING_APPROVAL（**不是 FAILED**）；</li>
 *   <li>重试（实体已带 signingApprovalId）⇒ **绝不再次调用 signTransfer**（不新建审批单），
 *       只查询既有审批状态并按状态分派；</li>
 *   <li>APPROVED ⇒ 走 sign-approved 执行；REJECTED/EXPIRED/不存在 ⇒ FAILED 且原因可辨；</li>
 *   <li>signing 已 EXECUTED 而本地无哈希 ⇒ EXECUTED_UNVERIFIED（可能已动账，不得 FAILED）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawalSigningApprovalReuseTest {

    private static final String PLATFORM_WALLET = "PLATFORM_HOT_WALLET";
    private static final String REQ_ID = "WD-reuse-0001";
    private static final String SIGNING_APPROVAL_ID = "SAP-0001";

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

    private WithdrawalRequestEntity seed(String status, String signingApprovalId) {
        WithdrawalRequestEntity e = new WithdrawalRequestEntity();
        e.setRequestId(REQ_ID);
        e.setToAddress(WalletUtils.pubkeyHashToAddress(
                "dddd5555deadbeefdeadbeefdeadbeefdeadbeef"));
        e.setAmount(new BigDecimal("50000"));
        e.setCurrency("NEX");
        e.setRequiredApprovers(2);
        e.setApprovedCount(2);
        e.setStatus(WithdrawalRequest.WithdrawalStatus.valueOf(status));
        e.setSigningApprovalId(signingApprovalId);
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

    // ==================== 首次执行：三态分类 ====================

    @Test
    @DisplayName("首次执行遇 2001：持久化 approvalId + AWAITING_APPROVAL（不是 FAILED）")
    void freshExecution_approvalRequired_persistsApprovalIdAndStatus() {
        WithdrawalRequestEntity e = seed("APPROVED", null);
        Map<String, Object> pending = new HashMap<>();
        pending.put("statusCode", 2001);
        pending.put("approvalId", SIGNING_APPROVAL_ID);
        pending.put("status", "PENDING");
        when(signingServiceClient.signTransfer(anyString(), anyString(), any())).thenReturn(pending);

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL, e.getStatus(),
                "2001 必须是待审批而非 FAILED——把待审批压成 FAILED 正是执行腿断链的根因");
        assertEquals(SIGNING_APPROVAL_ID, e.getSigningApprovalId());
        assertNull(e.getChainTxHash());
        verify(signingServiceClient).signTransfer(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("首次执行 2000+hash：EXECUTED 且不落 approvalId（既有语义不回归）")
    void freshExecution_success() {
        WithdrawalRequestEntity e = seed("APPROVED", null);
        when(signingServiceClient.signTransfer(anyString(), anyString(), any()))
                .thenReturn(envelope(2000, "0xhash-aaa", null));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, e.getStatus());
        assertEquals("0xhash-aaa", e.getChainTxHash());
        assertNotNull(e.getExecutedAt());
        assertNull(e.getSigningApprovalId());
    }

    @Test
    @DisplayName("首次执行 2001 但缺 approvalId：EXECUTED_UNVERIFIED（既不当待审批，也不当确定未动账）")
    void freshExecution_approvalRequiredWithoutId_isUnverified() {
        WithdrawalRequestEntity e = seed("APPROVED", null);
        when(signingServiceClient.signTransfer(anyString(), anyString(), any()))
                .thenReturn(envelope(2001, null, null));

        service.executeApprovedWithdrawal(REQ_ID);

        // 2026-10-31 fail-closed 收敛：signing 侧留下了一条**无凭据可跟踪**的审批请求，
        // 记 FAILED 会被人工重提 ⇒ 与该审批后续被执行构成二次出款；记待审批又无 id 可追。
        // 两者都不安全，落非终态 UNVERIFIED 交人工核对。
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus());
        assertNull(e.getSigningApprovalId());
    }

    // ==================== 重试：复用不新建（核心不变式） ====================

    @Test
    @DisplayName("重试（PENDING）：状态保持 AWAITING_APPROVAL，且绝不再次 signTransfer")
    void retry_pending_doesNotCreateSecondApproval() {
        WithdrawalRequestEntity e = seed("AWAITING_APPROVAL", SIGNING_APPROVAL_ID);
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("PENDING"));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL, e.getStatus());
        assertEquals(SIGNING_APPROVAL_ID, e.getSigningApprovalId());
        verify(signingServiceClient, never()).signTransfer(anyString(), anyString(), any());
        verify(signingServiceClient).getApprovalStatus(SIGNING_APPROVAL_ID);
    }

    @Test
    @DisplayName("重试（APPROVED）：走 sign-approved 执行，且绝不 signTransfer")
    void retry_approved_executesViaSignApproved() {
        WithdrawalRequestEntity e = seed("AWAITING_APPROVAL", SIGNING_APPROVAL_ID);
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("APPROVED"));
        when(signingServiceClient.executeApproved(SIGNING_APPROVAL_ID))
                .thenReturn(envelope(2000, "0xhash-approved", null));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, e.getStatus());
        assertEquals("0xhash-approved", e.getChainTxHash());
        verify(signingServiceClient).executeApproved(SIGNING_APPROVAL_ID);
        verify(signingServiceClient, never()).signTransfer(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("重试（REJECTED）：FAILED 且原因指向审批被拒（非签名失败）")
    void retry_rejected_failsWithApprovalReason() {
        WithdrawalRequestEntity e = seed("AWAITING_APPROVAL", SIGNING_APPROVAL_ID);
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("REJECTED"));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, e.getStatus());
        assertNotNull(e.getRejectionReason());
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getRejectionReason().contains("rejected"), "原因须可辨：审批被拒而非签名失败");
        verify(signingServiceClient, never()).signTransfer(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("重试（审批已不存在 404/降级）：FAILED，原因写明 not found（链上确认未动账）")
    void retry_approvalNotFound_fails() {
        WithdrawalRequestEntity e = seed("AWAITING_APPROVAL", SIGNING_APPROVAL_ID);
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID)).thenReturn(null);

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, e.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(
                e.getRejectionReason().contains("not found"), "原因须写明审批不存在");
        verify(signingServiceClient, never()).signTransfer(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("重试（signing 已 EXECUTED 但本地无哈希）：EXECUTED_UNVERIFIED，绝不判 FAILED")
    void retry_signingAlreadyExecuted_marksUnverified() {
        WithdrawalRequestEntity e = seed("AWAITING_APPROVAL", SIGNING_APPROVAL_ID);
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("EXECUTED"));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus(),
                "signing 已执行 = 可能已动账：不得判 FAILED、不得自动重放，须人工核对");
        verify(signingServiceClient, never()).signTransfer(anyString(), anyString(), any());
        verify(signingServiceClient, never()).executeApproved(anyString());
    }
}