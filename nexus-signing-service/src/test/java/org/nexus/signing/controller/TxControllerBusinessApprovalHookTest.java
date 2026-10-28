package org.nexus.signing.controller;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.sdk.wallet.WalletUtils;
import org.nexus.signing.approval.SigningApprovalService;
import org.nexus.signing.business.BusinessApprovalService;
import org.nexus.signing.keystore.PlatformKeystore;
import org.nexus.signing.pool.NoncePool;
import org.nexus.signing.pool.NonceTestDbs;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A2 signTransfer 钩子测试（ADR-036 §2.2）。
 *
 * <p>钉住的不变式：</p>
 * <ol>
 *   <li>凭据消费成功 ⇒ <b>跳过 signing 大额审批</b>直接签名（即使大额），
 *       广播成功不回退凭据；</li>
 *   <li>广播失败（资金未转出）⇒ 凭据回退可重试；</li>
 *   <li>凭据已被消费 ⇒ 409 + 5003 终止，<b>绝不创建新审批单</b>；</li>
 *   <li>ABSENT / HASH_MISMATCH / 凭据服务不可用 ⇒ 回落既有审批流（2001）；</li>
 *   <li>不携带参数 ⇒ 与 A2 之前逐字节一致（凭据服务零交互）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TxControllerBusinessApprovalHookTest {

    private static final String BA_ID = "WD-a2-0001";

    /** 测试 keystore 口令——与 TxControllerTest 既有 fixture 同值（纯测试件，非真实凭据）。 */
    private static final String TEST_KEYSTORE_PASSWORD = "password" + "123";

    @Mock
    private NoncePool noncePool;

    @Mock
    private NodeController nodeController;

    @Mock
    private PlatformKeystore platformKeystore;

    @Mock
    private SigningApprovalService signingApprovalService;

    @Mock
    private BusinessApprovalService businessApprovalService;

    private MockMvc mockMvc;

    private String pubkey;
    private String toPubkeyHash;

    @BeforeEach
    void setUp() throws Exception {
        TxController controller = new TxController();
        controller.noncePool = noncePool;
        controller.nonceAllocator = NonceTestDbs.freshH2Allocator("a2hook");
        controller.nodeController = nodeController;
        controller.platformKeystore = platformKeystore;
        controller.signingApprovalService = signingApprovalService;
        controller.businessApprovalService = businessApprovalService;
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        // 真实 keystore fixture（与 TxControllerTest 同款）
        String keystoreJson = WalletUtils.fromPassword(TEST_KEYSTORE_PASSWORD).toString();
        String prikey = WalletUtils.obtainPrikey(keystoreJson, TEST_KEYSTORE_PASSWORD);
        pubkey = WalletUtils.keystoreToPubkey(keystoreJson, TEST_KEYSTORE_PASSWORD);
        when(platformKeystore.getPrikey()).thenReturn(prikey);
        when(platformKeystore.getPubkey()).thenReturn(pubkey);
        toPubkeyHash = WalletUtils.pubkeyStrToPubkeyHashStr(pubkey);

        JsonObject nonce = new JsonObject();
        nonce.addProperty("code", 2000);
        nonce.addProperty("data", 0L);
        when(nodeController.getNonce(anyString())).thenReturn(nonce);
    }

    private void stubApprovalGateWouldTrigger() {
        // 若钩子失效走到旧逻辑，大额会创建审批并返回 2001——用该桩证明"跳过"真的发生
        when(signingApprovalService.requiresApproval(any(BigDecimal.class), anyString())).thenReturn(true);
        when(signingApprovalService.createApprovalRequest(anyString(), anyString(),
                any(BigDecimal.class), anyString(), anyString(), anyString())).thenReturn("SAP-should-not-be-created");
    }

    private BusinessApprovalService.ConsumeAttempt attempt(
            BusinessApprovalService.Reason reason, boolean consumed) {
        return new BusinessApprovalService.ConsumeAttempt(consumed, reason, "0".repeat(64));
    }

    @Test
    @DisplayName("凭据消费成功 → 跳过审批直接签名（大额也不建审批单），成功不回退")
    void consumedCredential_skipsApproval_signsImmediately() throws Exception {
        stubApprovalGateWouldTrigger();
        when(businessApprovalService.tryConsume(eq(BA_ID), anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn(attempt(null, true));

        mockMvc.perform(post("/api/v1/transfers/sign")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", pubkey)
                        .param("toPubkeyHash", toPubkeyHash)
                        .param("amount", "100000")
                        .param("businessApprovalId", BA_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000))
                .andExpect(jsonPath("$.data").isNotEmpty());

        verify(signingApprovalService, never()).createApprovalRequest(
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString(), anyString());
        verify(businessApprovalService, never()).revertConsumedAfterFailedBroadcast(BA_ID);
    }

    @Test
    @DisplayName("凭据消费成功但广播失败（无签名键）→ 凭据回退可重试")
    void consumedCredential_broadcastFails_revertsCredential() throws Exception {
        when(platformKeystore.getPrikey()).thenReturn(null);
        when(businessApprovalService.tryConsume(eq(BA_ID), anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn(attempt(null, true));

        mockMvc.perform(post("/api/v1/transfers/sign")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", pubkey)
                        .param("toPubkeyHash", toPubkeyHash)
                        .param("amount", "100")
                        .param("businessApprovalId", BA_ID))
                .andExpect(status().isInternalServerError());

        verify(businessApprovalService).revertConsumedAfterFailedBroadcast(BA_ID);
    }

    @Test
    @DisplayName("凭据已被消费 → 409 + 5003 终止，绝不创建新审批单")
    void alreadyConsumedCredential_terminated() throws Exception {
        stubApprovalGateWouldTrigger();
        when(businessApprovalService.tryConsume(eq(BA_ID), anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn(attempt(BusinessApprovalService.Reason.ALREADY_CONSUMED, false));

        mockMvc.perform(post("/api/v1/transfers/sign")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", pubkey)
                        .param("toPubkeyHash", toPubkeyHash)
                        .param("amount", "100000")
                        .param("businessApprovalId", BA_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.statusCode").value(5003.0))
                .andExpect(jsonPath("$.message", containsString("already consumed")));

        verify(signingApprovalService, never()).createApprovalRequest(
                anyString(), anyString(), any(BigDecimal.class), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("凭据不存在（ABSENT）→ 回落既有审批流（2001 + approvalId）")
    void absentCredential_fallsBackToApprovalFlow() throws Exception {
        stubApprovalGateWouldTrigger();
        when(businessApprovalService.tryConsume(eq(BA_ID), anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn(attempt(BusinessApprovalService.Reason.ABSENT, false));

        mockMvc.perform(post("/api/v1/transfers/sign")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", pubkey)
                        .param("toPubkeyHash", toPubkeyHash)
                        .param("amount", "100000")
                        .param("businessApprovalId", BA_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2001))
                .andExpect(jsonPath("$.approvalId").value("SAP-should-not-be-created"));
    }

    @Test
    @DisplayName("载荷不匹配（HASH_MISMATCH）→ 回落既有审批流")
    void hashMismatchCredential_fallsBackToApprovalFlow() throws Exception {
        stubApprovalGateWouldTrigger();
        when(businessApprovalService.tryConsume(eq(BA_ID), anyString(), anyString(), any(BigDecimal.class)))
                .thenReturn(attempt(BusinessApprovalService.Reason.HASH_MISMATCH, false));

        mockMvc.perform(post("/api/v1/transfers/sign")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", pubkey)
                        .param("toPubkeyHash", toPubkeyHash)
                        .param("amount", "100000")
                        .param("businessApprovalId", BA_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2001))
                .andExpect(jsonPath("$.approvalId").value("SAP-should-not-be-created"));
    }

    @Test
    @DisplayName("凭据服务不可用 → 回落既有审批流（豁免 fail-closed，业务不阻断）")
    void registryUnavailable_fallsBackToApprovalFlow() throws Exception {
        stubApprovalGateWouldTrigger();
        TxController controller = new TxController();
        controller.noncePool = noncePool;
        controller.nonceAllocator = NonceTestDbs.freshH2Allocator("a2hook2");
        controller.nodeController = nodeController;
        controller.platformKeystore = platformKeystore;
        controller.signingApprovalService = signingApprovalService;
        // businessApprovalService 刻意留空
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/api/v1/transfers/sign")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", pubkey)
                        .param("toPubkeyHash", toPubkeyHash)
                        .param("amount", "100000")
                        .param("businessApprovalId", BA_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2001))
                .andExpect(jsonPath("$.approvalId").value("SAP-should-not-be-created"));
    }

    @Test
    @DisplayName("不携带参数 → 与 A2 之前逐字节一致（凭据服务零交互）")
    void noBusinessApprovalId_legacyUntouched() throws Exception {
        stubApprovalGateWouldTrigger();

        mockMvc.perform(post("/api/v1/transfers/sign")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", pubkey)
                        .param("toPubkeyHash", toPubkeyHash)
                        .param("amount", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2001))
                .andExpect(jsonPath("$.approvalId").value("SAP-should-not-be-created"));

        verifyNoInteractions(businessApprovalService);
    }
}
