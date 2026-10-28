package org.nexus.walletsvc.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.signing.ApprovalPolicy;
import org.nexus.sdk.signing.BusinessApprovalPayloads;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.entity.WithdrawalRequestEntity;
import org.nexus.walletsvc.repository.WithdrawalApproverRepository;
import org.nexus.walletsvc.repository.WithdrawalRequestRepository;
import org.nexus.walletsvc.repository.WhitelistEntryRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2 首腿用例（ADR-036 §2.2，2026-10-28 立项实施）。
 *
 * <p>钉住的不变式：</p>
 * <ol>
 *   <li>开关默认关闭 ⇒ 旧行为逐字节不变（绝不登记、绝不携凭据）；</li>
 *   <li>开关开启 ⇒ 先登记（canonical 摘要绑定载荷、requestId 作凭据号）再携凭据签名，
 *       登记先于签名（InOrder）；</li>
 *   <li>登记失败 / 降级 / 非 2000 ⇒ 回落既有 signing 审批流（此刻无资金动作，人审兜底）；</li>
 *   <li>携凭据签名<b>结果不明</b>（降级 null / 5003 凭据已消费 / 抛异常）⇒
 *       EXECUTED_UNVERIFIED，<b>绝不回落重试</b>（凭据已被 CAS 消费，上次可能已广播）；</li>
 *   <li>signing 回落返回 2001 ⇒ AWAITING_APPROVAL，与旧行为汇合（(c)-lite 接管）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawalBusinessApprovalLegTest {

    private static final String PLATFORM_WALLET = "0xplatform0000000000000000000000deadbeef";
    private static final String REQ_ID = "WD-a2-0001";
    private static final String TO_ADDR = "0xrecipient000000000000";
    private static final BigDecimal AMOUNT = new BigDecimal("50000");

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

        ApprovalPolicy policy = new DefaultApprovalPolicy(whitelistEntryRepository);
        service = new DefaultWithdrawalApprovalService(
                policy, signingServiceClient, PLATFORM_WALLET,
                withdrawalRequestRepository, withdrawalApproverRepository);
    }

    private WithdrawalRequestEntity seedApproved() {
        WithdrawalRequestEntity e = new WithdrawalRequestEntity();
        e.setRequestId(REQ_ID);
        e.setToAddress(TO_ADDR);
        e.setAmount(AMOUNT);
        e.setCurrency("NEX");
        e.setRequiredApprovers(2);
        e.setApprovedCount(2);
        e.setStatus(WithdrawalRequest.WithdrawalStatus.APPROVED);
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

    private static Map<String, Object> registeredOk() {
        Map<String, Object> data = new HashMap<>();
        data.put("businessApprovalId", REQ_ID);
        data.put("state", "REGISTERED");
        return envelope(2000, data, null);
    }

    // ==================== 开关关闭：旧行为逐字节不变 ====================

    @Test
    @DisplayName("开关默认关闭：直接 signTransfer，绝不登记、绝不携凭据")
    void flagOff_legacyBehaviorUntouched() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.signTransfer(PLATFORM_WALLET, TO_ADDR, AMOUNT))
                .thenReturn(envelope(2000, "0xhash-plain", null));

        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, e.getStatus());
        assertEquals("0xhash-plain", e.getChainTxHash());
        verify(signingServiceClient, never()).registerBusinessApproval(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(signingServiceClient, never()).signTransferWithBusinessApproval(
                any(), any(), any(), any());
    }

    // ==================== 开关开启：登记 → 携凭据签名 ====================

    @Test
    @DisplayName("开关开启：先登记（载荷绑定 requestId）再携凭据签名，成功直接 EXECUTED")
    void flagOn_registerThenSign_executed() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                eq(REQ_ID), anyString(), eq(AMOUNT), eq("NEX"),
                eq(PLATFORM_WALLET), eq(TO_ADDR), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_WALLET, TO_ADDR, AMOUNT, REQ_ID))
                .thenReturn(envelope(2000, "0xhash-ba", null));

        service.setBusinessApprovalEnabled(true);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, e.getStatus());
        assertEquals("0xhash-ba", e.getChainTxHash());
        InOrder order = inOrder(signingServiceClient);
        order.verify(signingServiceClient).registerBusinessApproval(
                eq(REQ_ID), anyString(), eq(AMOUNT), eq("NEX"),
                eq(PLATFORM_WALLET), eq(TO_ADDR), anyString(), isNull());
        order.verify(signingServiceClient).signTransferWithBusinessApproval(
                PLATFORM_WALLET, TO_ADDR, AMOUNT, REQ_ID);
    }

    @Test
    @DisplayName("登记的 payloadHash 必须是 canonical 摘要（双侧共享算法，防载荷替换）")
    void registeredHash_isCanonicalPayloadDigest() {
        seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                anyString(), anyString(), any(), anyString()))
                .thenReturn(envelope(2000, "0xhash-ba", null));

        service.setBusinessApprovalEnabled(true);
        service.executeApprovedWithdrawal(REQ_ID);

        org.mockito.ArgumentCaptor<String> hashCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(signingServiceClient).registerBusinessApproval(
                eq(REQ_ID), hashCaptor.capture(), eq(AMOUNT), eq("NEX"),
                eq(PLATFORM_WALLET), eq(TO_ADDR), anyString(), isNull());
        assertEquals(BusinessApprovalPayloads.canonicalHash(
                        PLATFORM_WALLET, TO_ADDR, AMOUNT, REQ_ID),
                hashCaptor.getValue());
    }

    @Test
    @DisplayName("登记的 expiresAt 由可配 TTL 派生（默认 24h，setter 可改）")
    void registeredExpiry_derivedFromTtl() {
        seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                anyString(), anyString(), any(), anyString()))
                .thenReturn(envelope(2000, "0xhash-ba", null));

        service.setBusinessApprovalEnabled(true);
        service.setBusinessApprovalTtlHours(2);
        long before = Instant.now().toEpochMilli();
        service.executeApprovedWithdrawal(REQ_ID);
        long after = Instant.now().toEpochMilli();

        org.mockito.ArgumentCaptor<String> expiryCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(signingServiceClient).registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(),
                expiryCaptor.capture(), isNull());
        Instant parsed = Instant.parse(expiryCaptor.getValue());
        // 约 now + 2h（允许时钟推进误差 10s）
        assertTrue(parsed.toEpochMilli() >= before + 2 * 3600_000L - 10_000,
                "expiresAt 应约为 now+2h: " + parsed);
        assertTrue(parsed.toEpochMilli() <= after + 2 * 3600_000L + 10_000);
    }

    // ==================== 登记失败：回落既有审批流 ====================

    @Test
    @DisplayName("登记抛异常 → 回落既有审批流（此刻无资金动作，人审兜底）")
    void registerThrows_fallsBackToLegacyApprovalFlow() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenThrow(new RuntimeException("signing connect timeout"));
        when(signingServiceClient.signTransfer(PLATFORM_WALLET, TO_ADDR, AMOUNT))
                .thenAnswer(inv -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("statusCode", 2001);
                    m.put("approvalId", "SAP-fallback-1");
                    return m;
                });

        service.setBusinessApprovalEnabled(true);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL, e.getStatus());
        assertEquals("SAP-fallback-1", e.getSigningApprovalId());
        verify(signingServiceClient, never()).signTransferWithBusinessApproval(
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("登记降级（null）/ 冲突（5002）→ 同样回落既有审批流")
    void registerDegradeOrConflict_fallsBackToLegacy() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(null)
                .thenReturn(envelope(5002, null, "payload hash conflict"));
        when(signingServiceClient.signTransfer(PLATFORM_WALLET, TO_ADDR, AMOUNT))
                .thenAnswer(inv -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("statusCode", 2001);
                    m.put("approvalId", "SAP-fallback-2");
                    return m;
                });

        service.setBusinessApprovalEnabled(true);
        service.executeApprovedWithdrawal(REQ_ID);
        assertEquals(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL, e.getStatus());

        // 第二次（5002 冲突）也回落：先清掉首次的 AWAITING 状态语义（新 seed 同款实体）
        clearInvocations(signingServiceClient);
        WithdrawalRequestEntity e2 = new WithdrawalRequestEntity();
        e2.setRequestId("WD-a2-0002");
        e2.setToAddress(TO_ADDR);
        e2.setAmount(AMOUNT);
        e2.setCurrency("NEX");
        e2.setStatus(WithdrawalRequest.WithdrawalStatus.APPROVED);
        requestStore.put("WD-a2-0002", e2);
        service.executeApprovedWithdrawal("WD-a2-0002");
        assertEquals(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL, e2.getStatus());
        assertEquals("SAP-fallback-2", e2.getSigningApprovalId());
    }

    // ==================== 携凭据签名结果不明：绝不回落重试 ====================

    @Test
    @DisplayName("携凭据签名降级（null）→ EXECUTED_UNVERIFIED，绝不回落 plain signTransfer")
    void signCallDegrades_executedUnverified_neverRetries() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_WALLET, TO_ADDR, AMOUNT, REQ_ID))
                .thenReturn(null);

        service.setBusinessApprovalEnabled(true);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus(),
                "凭据已被 CAS 消费，签名可能已广播：降级 null 是结果不明，不是失败");
        assertTrue(e.getRejectionReason().contains("possible prior broadcast"),
                "异常原因必须提示可能已广播: " + e.getRejectionReason());
        verify(signingServiceClient, never()).signTransfer(
                any(), any(), any());
    }

    @Test
    @DisplayName("signing 返回 5003（凭据已消费）→ EXECUTED_UNVERIFIED（不回落人审）")
    void signing5003_executedUnverified() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_WALLET, TO_ADDR, AMOUNT, REQ_ID))
                .thenReturn(envelope(5003, null,
                        "business approval already consumed (possible prior broadcast)"));

        service.setBusinessApprovalEnabled(true);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus());
        assertTrue(e.getRejectionReason().contains("already consumed"));
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    @Test
    @DisplayName("携凭据签名抛异常 → EXECUTED_UNVERIFIED 保留人工核对语义")
    void signCallThrows_executedUnverified() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_WALLET, TO_ADDR, AMOUNT, REQ_ID))
                .thenThrow(new RuntimeException("socket reset"));

        service.setBusinessApprovalEnabled(true);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus());
        assertNotNull(e.getRejectionReason());
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    // ==================== signing 回落 2001：与旧行为汇合 ====================

    @Test
    @DisplayName("signing 豁免校验未过回落建审批（2001）→ AWAITING_APPROVAL，(c)-lite 接管")
    void signingFallsBackToItsOwnApproval_awaitingApproval() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_WALLET, TO_ADDR, AMOUNT, REQ_ID))
                .thenAnswer(inv -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("statusCode", 2001);
                    m.put("approvalId", "SAP-signing-fallback");
                    return m;
                });

        service.setBusinessApprovalEnabled(true);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL, e.getStatus());
        assertEquals("SAP-signing-fallback", e.getSigningApprovalId());
    }

    // ==================== 明确失败：FAILED ====================

    @Test
    @DisplayName("携凭据签名明确失败（5xxx 预广播失败，凭据已被回退）→ FAILED")
    void signingExplicitFailure_failed() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_WALLET, TO_ADDR, AMOUNT, REQ_ID))
                .thenReturn(envelope(5000, null, "No signing key available"));

        service.setBusinessApprovalEnabled(true);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, e.getStatus(),
                "预广播失败 = 确定未动账（signing 侧已回退凭据），按 FAILED 走既有重试语义");
    }
}
