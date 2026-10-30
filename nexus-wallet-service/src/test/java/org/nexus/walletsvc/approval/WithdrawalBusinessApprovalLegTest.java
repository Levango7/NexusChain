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
import org.nexus.sdk.wallet.WalletUtils;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 *   <li>携凭据签名<b>结果不明</b>（降级 null / 5003 凭据已消费 / 抛不可解析异常）⇒
 *       EXECUTED_UNVERIFIED，<b>绝不回落重试</b>（凭据已被 CAS 消费，上次可能已广播）；
 *       抛 FeignException 但响应体带<b>已定义未动账码</b>（5000/4000/4030）⇒ FAILED
 *       （A2 §7.5 决策一，P0-2：signing 以 HTTP 非 2xx 携信封返回业务失败；
 *       5001 不在其列——另一路持执行权且可能正在广播，谓词按结果不明处理）；</li>
 *   <li>signing 回落返回 2001 ⇒ AWAITING_APPROVAL，与旧行为汇合（(c)-lite 接管）；</li>
 *   <li>词表对齐（§7.5 ③）：from 用 platform-pubkey、to 用地址转换出的 pubkeyHash，
 *       canonicalHash/register/sign 三处一致；platform-pubkey 未配 ⇒ fail-closed。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WithdrawalBusinessApprovalLegTest {

    /**
     * A2 §7.5 ③ 词表对齐（2026-10-29）：signing 侧签名接口的参数空间是
     * fromPubkey（完整公钥 hex）/ toPubkeyHash（40-hex），实体/配置侧存的是 base58 地址。
     * 测试常量按真实口径构造：TO_ADDR 由 TO_PUBKEY_HASH 经 base58 编码得来，
     * 服务侧再转回 pubkeyHash 发给 signing（往返即同一值）。
     */
    private static final String PLATFORM_WALLET = "0xplatform0000000000000000000000deadbeef";
    private static final String PLATFORM_PUBKEY =
            "02abababababababababababababababababababababababababababababababab";
    private static final String TO_PUBKEY_HASH = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
    private static final String TO_ADDR = WalletUtils.pubkeyHashToAddress(TO_PUBKEY_HASH);
    private static final String REQ_ID = "WD-a2-0001";
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
        // 开关关闭且未配 platform-pubkey ⇒ from 沿用旧行为传 platform-address，
        // to 转成 pubkeyHash（词表对齐）
        when(signingServiceClient.signTransfer(PLATFORM_WALLET, TO_PUBKEY_HASH, AMOUNT))
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
                eq(PLATFORM_PUBKEY), eq(TO_PUBKEY_HASH), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenReturn(envelope(2000, "0xhash-ba", null));

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED, e.getStatus());
        assertEquals("0xhash-ba", e.getChainTxHash());
        InOrder order = inOrder(signingServiceClient);
        order.verify(signingServiceClient).registerBusinessApproval(
                eq(REQ_ID), anyString(), eq(AMOUNT), eq("NEX"),
                eq(PLATFORM_PUBKEY), eq(TO_PUBKEY_HASH), anyString(), isNull());
        order.verify(signingServiceClient).signTransferWithBusinessApproval(
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID);
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
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
        service.executeApprovedWithdrawal(REQ_ID);

        org.mockito.ArgumentCaptor<String> hashCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(signingServiceClient).registerBusinessApproval(
                eq(REQ_ID), hashCaptor.capture(), eq(AMOUNT), eq("NEX"),
                eq(PLATFORM_PUBKEY), eq(TO_PUBKEY_HASH), anyString(), isNull());
        assertEquals(BusinessApprovalPayloads.canonicalHash(
                        PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID),
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
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
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
        // 回落后首腿收到的已是转换后的词表（from=公钥 / to=pubkeyHash）
        when(signingServiceClient.signTransfer(PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT))
                .thenAnswer(inv -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("statusCode", 2001);
                    m.put("approvalId", "SAP-fallback-1");
                    return m;
                });

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
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
        when(signingServiceClient.signTransfer(PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT))
                .thenAnswer(inv -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("statusCode", 2001);
                    m.put("approvalId", "SAP-fallback-2");
                    return m;
                });

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
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
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenReturn(null);

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
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
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenReturn(envelope(5003, null,
                        "business approval already consumed (possible prior broadcast)"));

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus());
        assertTrue(e.getRejectionReason().contains("already consumed"));
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    @Test
    @DisplayName("signing 以 200 信封回 5001（并发执行冲突）→ EXECUTED_UNVERIFIED，不落终态 FAILED")
    void signing5001OnEnvelopePath_executedUnverified() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenReturn(envelope(5001, null,
                        "approval execution claimed by another request"));

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
        service.executeApprovedWithdrawal(REQ_ID);

        // 5001 = 另一路持执行权且可能正在广播；fail-closed 词表不认它是「确定未动账」，
        // 终态 FAILED 会让运维以为钱没出去。此前 Map 路径只特例 null/5003，5001 会漏成 FAILED。
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus(),
                "5001 must not become terminal FAILED on the envelope path");
        assertTrue(e.getRejectionReason().contains("code=5001"), e.getRejectionReason());
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    @Test
    @DisplayName("signing 回未知码（5999）→ EXECUTED_UNVERIFIED：白名单之外一律不得乐观落 FAILED")
    void signingUnknownCodeOnEnvelopePath_executedUnverified() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenReturn(envelope(5999, null, "some future signing failure"));

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
        service.executeApprovedWithdrawal(REQ_ID);

        // 未来新增码的语义未被契约确立 ⇒ 结果不明（与异常腿同一判据），禁止当未动账重放。
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus(),
                "codes outside the not-executed vocabulary must stay EXECUTED_UNVERIFIED");
        assertTrue(e.getRejectionReason().contains("code=5999"), e.getRejectionReason());
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
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenThrow(new RuntimeException("socket reset"));

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
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
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenAnswer(inv -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("statusCode", 2001);
                    m.put("approvalId", "SAP-signing-fallback");
                    return m;
                });

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
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
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenReturn(envelope(5000, null, "No signing key available"));

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, e.getStatus(),
                "预广播失败 = 确定未动账（signing 侧已回退凭据），按 FAILED 走既有重试语义");
    }

    // ==================== A2 §7.5 决策一：异常路径三态映射（P0-2 配套，2026-10-29） ====================

    /**
     * 构造一个携带 JSON 业务信封的 FeignException——signing 以 HTTP 非 2xx 返回业务失败
     * （GlobalExceptionHandler：BIZ_FAIL→500 / BIZ_BAD_REQUEST→400 / BIZ_FORBIDDEN→403），
     * Feign 因此抛异常而不是返回信封；三态映射必须能从异常响应体里把 statusCode 抠出来。
     */
    private static feign.FeignException feignBusinessFailure(String body) {
        return new feign.FeignException.InternalServerError(
                "signing-service returned 500",
                feign.Request.create(feign.Request.HttpMethod.POST,
                        "http://signing-service/api/v1/transfers/sign",
                        java.util.Collections.emptyMap(), new byte[0],
                        java.nio.charset.StandardCharsets.UTF_8),
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.util.Collections.emptyMap());
    }

    @Test
    @DisplayName("携凭据签名抛 FeignException 且响应体=5000（明确未动账）→ FAILED")
    void signCallThrowsFeign5000_failed() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenThrow(feignBusinessFailure(
                        "{\"statusCode\":5000,\"message\":\"No signing key available\",\"data\":\"\"}"));

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, e.getStatus(),
                "响应体里的 5000 证明确定未动账（signing 已回退凭据）：应判 FAILED 走重试语义");
        assertTrue(e.getRejectionReason().contains("confirmed not executed"),
                "失败原因必须写明确定未动账: " + e.getRejectionReason());
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    @Test
    @DisplayName("携凭据签名抛 FeignException 且响应体=5003（凭据已消费）→ EXECUTED_UNVERIFIED")
    void signCallThrowsFeign5003_executedUnverified() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenThrow(feignBusinessFailure(
                        "{\"statusCode\":5003,\"message\":\"business approval already consumed\",\"data\":\"\"}"));

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus(),
                "5003 表示凭据可能已被消费并广播：不判 FAILED、不自动重放");
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    @Test
    @DisplayName("携凭据签名抛 FeignException 且响应体=5001（并发执行冲突）→ EXECUTED_UNVERIFIED，不当未动账")
    void signCallThrowsFeign5001_executedUnverified_neverTreatedAsNotExecuted() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenThrow(feignBusinessFailure(
                        "{\"statusCode\":5001,\"message\":\"execution conflict: another caller holds the lock\",\"data\":\"\"}"));

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
        service.executeApprovedWithdrawal(REQ_ID);

        // 5001 的唯一出处是 sign-approved 路径的 tryMarkExecuting CAS 失败——本调用虽未执行，
        // 但抢到执行权的另一路可能正在广播。declaresNotExecuted 对 5001 返回 false（fail-closed
        // 词表）：绝不写成「confirmed not executed」，保持结果不明交人工核对。
        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus(),
                "5001 表示另一路可能正在广播：不判 FAILED、不自动重放");
        assertFalse(e.getRejectionReason().contains("confirmed not executed"),
                "结果不明的码不得写成「确定未动账」: " + e.getRejectionReason());
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    @Test
    @DisplayName("携凭据签名抛 FeignException 但响应体不可解析 → EXECUTED_UNVERIFIED（fail-closed）")
    void signCallThrowsFeignUnparseableBody_executedUnverified() {
        WithdrawalRequestEntity e = seedApproved();
        when(signingServiceClient.registerBusinessApproval(
                anyString(), anyString(), any(), any(), any(), any(), anyString(), isNull()))
                .thenReturn(registeredOk());
        when(signingServiceClient.signTransferWithBusinessApproval(
                PLATFORM_PUBKEY, TO_PUBKEY_HASH, AMOUNT, REQ_ID))
                .thenThrow(feignBusinessFailure("<html>503 Service Unavailable</html>"));

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED, e.getStatus(),
                "不可解析 ⇒ 无法证明未动账：保持结果不明，绝不自动重放");
        assertTrue(e.getRejectionReason().contains("possible prior broadcast"),
                "原因必须提示可能已广播: " + e.getRejectionReason());
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    // ==================== A2 §7.5 ③ 词表对齐 fail-closed（2026-10-29） ====================

    @Test
    @DisplayName("A2 开启但 platform-pubkey 未配置 → fail-closed FAILED，绝不以地址冒充公钥")
    void a2OnWithoutPlatformPubkey_failClosed() {
        WithdrawalRequestEntity e = seedApproved();

        service.setBusinessApprovalEnabled(true);
        // 不调 setPlatformWalletPubkey：公钥不可由 base58 地址反推，冒充必错
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, e.getStatus());
        assertTrue(e.getRejectionReason().contains("platform wallet pubkey not configured"),
                "原因必须明写缺失的配置项: " + e.getRejectionReason());
        verify(signingServiceClient, never()).registerBusinessApproval(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(signingServiceClient, never()).signTransferWithBusinessApproval(
                any(), any(), any(), any());
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }

    @Test
    @DisplayName("收款地址不是合法 base58 → fail-closed FAILED，绝不发签名")
    void destinationNotBase58_failClosed() {
        WithdrawalRequestEntity e = seedApproved();
        e.setToAddress("not-a-base58-address");

        service.setBusinessApprovalEnabled(true);
        service.setPlatformWalletPubkey(PLATFORM_PUBKEY);
        service.executeApprovedWithdrawal(REQ_ID);

        assertEquals(WithdrawalRequest.WithdrawalStatus.FAILED, e.getStatus());
        assertTrue(e.getRejectionReason().contains("cannot derive toPubkeyHash"),
                "原因必须明写收款地址非法: " + e.getRejectionReason());
        verify(signingServiceClient, never()).registerBusinessApproval(
                any(), any(), any(), any(), any(), any(), any(), any());
        verify(signingServiceClient, never()).signTransferWithBusinessApproval(
                any(), any(), any(), any());
        verify(signingServiceClient, never()).signTransfer(any(), any(), any());
    }
}
