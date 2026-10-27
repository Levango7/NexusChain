package org.nexus.walletsvc.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.sdk.client.feign.SigningResponses;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.wallet.WithdrawalRequest;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * signing 审批「过期信号」在 wallet 侧的处置口径。
 *
 * <p>来源：PR #280 的 expired-flag 用例矩阵（该 PR 已关闭——内容被 #285/#294 取代，
 * 见板 20261027-1646 / 20261027-1725）。本类按<b>主干 resumeSigningApproval 实现</b>
 * 重写，只加测试、不改产品码。</p>
 *
 * <p>钉住的不变式：</p>
 * <ol>
 *   <li>{@code status=EXPIRED} ⇒ FAILED（确定未动账），<b>不得</b>标成 EXECUTED_UNVERIFIED；</li>
 *   <li>{@code status=EXECUTING}（另一路正在执行）⇒ 保持 AWAITING_APPROVAL 等待，
 *       不抢先 executeApproved、不写 attemptedAt；</li>
 *   <li>{@code status=EXECUTED} 且 {@code expired=true}（防御性组合）⇒ 仍 EXECUTED_UNVERIFIED，
 *       过期标记不得把「钱可能已出」降级成 FAILED；</li>
 *   <li>{@code status=PENDING} 且 {@code expired=true} ⇒ 当前实现<b>仍等待</b>（只读 data.status，
 *       不读 data.expired）。signing 清理阶段会把 PENDING+过 TTL 翻成 EXPIRED
 *       （nexus-signing-service SigningApprovalService 清理阶段 1），下一轮推进即落到用例 1 的 FAILED。
 *       #280 原期望是「读到 expired 就直接 FAILED」，那是产品码变更，本 PR 不做。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawalSigningExpiredSignalTest {

    private static final String PLATFORM_WALLET = "PLATFORM_HOT_WALLET";
    private static final String REQ_ID = "WD-expired-0001";
    private static final String SIGNING_APPROVAL_ID = "SAP-EXPIRED-0001";

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

    /** 种一笔「首次执行已拿到 2001、带着 approvalId 等推进」的提现。 */
    private void seedAwaitingApproval() {
        WithdrawalRequestEntity e = new WithdrawalRequestEntity();
        e.setRequestId(REQ_ID);
        e.setToAddress("0xrecipient000000000000");
        e.setAmount(new BigDecimal("50000"));
        e.setCurrency("NEX");
        e.setRequiredApprovers(2);
        e.setApprovedCount(2);
        e.setStatus(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL);
        e.setSigningApprovalId(SIGNING_APPROVAL_ID);
        requestStore.put(REQ_ID, e);
    }

    private static Map<String, Object> envelope(int statusCode, Object data) {
        Map<String, Object> m = new HashMap<>();
        m.put("statusCode", statusCode);
        if (data != null) {
            m.put("data", data);
        }
        return m;
    }

    private static Map<String, Object> approvalStatusEnvelope(String status, Boolean expired) {
        Map<String, Object> data = new HashMap<>();
        data.put("requestId", SIGNING_APPROVAL_ID);
        data.put("status", status);
        if (expired != null) {
            data.put("expired", expired);
        }
        return envelope(2000, data);
    }

    @Test
    @DisplayName("审批 status=EXPIRED ⇒ FAILED，且不标 EXECUTED_UNVERIFIED")
    void resume_whenSigningReportsExpired_failsWithoutUnverifiedTag() {
        seedAwaitingApproval();
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("EXPIRED", null));

        WithdrawalRequest result = service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, result.getStatus(),
                "审批过期是终态，必须收尾为 FAILED，不能永久停在 AWAITING_APPROVAL");
        assertNotNull(result.getRejectionReason());
        assertTrue(result.getRejectionReason().contains("expired"),
                "失败原因应指明过期，实得：" + result.getRejectionReason());
        assertNotEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus(),
                "过期 = 确定未动账，不得标成「钱可能已出去」");
        verify(signingServiceClient, times(1)).getApprovalStatus(SIGNING_APPROVAL_ID);
        verify(signingServiceClient, never()).executeApproved(anyString());
        verify(signingServiceClient, never()).signTransfer(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("审批 status=EXECUTING（另一路执行中）⇒ 保持 AWAITING_APPROVAL，不抢先执行")
    void resume_whenSigningReportsExecuting_keepsWaiting() {
        seedAwaitingApproval();
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("EXECUTING", null));

        WithdrawalRequest result = service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL, result.getStatus(),
                "对方正在执行 ⇒ 继续等，不能判失败也不能抢先执行");
        assertNull(result.getRejectionReason());
        verify(signingServiceClient, never()).executeApproved(anyString());
        verify(signingServiceClient, never()).signTransfer(anyString(), anyString(), any());
        assertNull(requestStore.get(REQ_ID).getSigningExecutionAttemptedAt(),
                "我们没发起执行，不得写 attemptedAt");
    }

    @Test
    @DisplayName("[防御性] status=EXECUTED 且 expired=true ⇒ 仍 EXECUTED_UNVERIFIED")
    void resume_whenExecutedWithExpiredFlag_stillUnverified() {
        seedAwaitingApproval();
        // 刻意不走 approvalStatusEnvelope：真实服务里 EXECUTED 与 expired=true 不会同时出现
        // （SigningApprovalRequest.isExpired() = status==PENDING && now>deadline）。
        // 本用例锁的是「将来 isExpired() 语义扩展时，不得把已执行的提现降级成 FAILED」。
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(Map.of("statusCode", 2000, SigningResponses.KEY_DATA,
                        Map.of("status", "EXECUTED", "expired", true)));

        WithdrawalRequest result = service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, result.getStatus(),
                "signing 已确认执行 ⇒ 钱已出，过期标记不得把它降级成 FAILED");
        verify(signingServiceClient, times(1)).getApprovalStatus(SIGNING_APPROVAL_ID);
        verify(signingServiceClient, never()).executeApproved(anyString());
    }

    @Test
    @DisplayName("[当前口径] status=PENDING 且 expired=true ⇒ 仍等待（不读 expired 字段）")
    void resume_whenPendingWithExpiredFlag_keepsWaiting() {
        seedAwaitingApproval();
        when(signingServiceClient.getApprovalStatus(SIGNING_APPROVAL_ID))
                .thenReturn(approvalStatusEnvelope("PENDING", true));

        WithdrawalRequest result = service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL, result.getStatus(),
                "当前实现只读 data.status；expired 标志不单独终态化");
        assertNull(result.getRejectionReason());
        verify(signingServiceClient, never()).executeApproved(anyString());

        // 自愈路径：signing 清理阶段 1 会把 PENDING+过 TTL 原地翻成 EXPIRED，
        // 下一轮推进即进入 resume_whenSigningReportsExpired_failsWithoutUnverifiedTag。
        // 本用例钉的是实现事实（防止无意变更），不是价值判断：若要改成「读 expired 直接失败」，
        // 属产品码变更，需同步本用例并走板面。
    }
}
