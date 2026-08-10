package org.nexus.signing.controller;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.sdk.wallet.WalletUtils;
import org.nexus.signing.keystore.PlatformKeystore;
import org.nexus.signing.pool.NoncePool;
import org.nexus.signing.pool.NonceState;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TxController} 安全回归测试。
 *
 * <p>从 {@code org.nexus.wallet.signing.controller.TxControllerTest}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.controller}。</p>
 *
 * <p>验证 P1 修复：私钥（prikey / keystoreJson+password）不再接受调用方直传，
 * 签名仅使用服务端平台托管密钥（{@link PlatformKeystore}），且 fromPubkey 必须
 * 与平台公钥一致。</p>
 */
@ExtendWith(MockitoExtension.class)
public class TxControllerTest {

    @Mock
    private NoncePool noncePool;

    @Mock
    private NodeController nodeController;

    @Mock
    private PlatformKeystore platformKeystore;

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        TxController controller = new TxController();
        controller.noncePool = noncePool;
        controller.nodeController = nodeController;
        controller.platformKeystore = platformKeystore;
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void testClientToTransferAccount_withoutPlatformKey_rejectsEvenWithPrikeyParam() throws Exception {
        // 平台密钥未配置 + 调用方夹带明文 prikey 参数 → 必须拒绝，且 prikey 被忽略
        when(platformKeystore.getPrikey()).thenReturn(null);

        mockMvc.perform(post("/ClientToTransferAccount")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", "aa")
                        .param("toPubkeyHash", "bb")
                        .param("amount", "100")
                        .param("prikey", "attacker-supplied-plaintext-private-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(5000.0))
                .andExpect(jsonPath("$.message", containsString("No signing key available")));
    }

    @Test
    public void testClientToTransferAccount_fromPubkeyMismatch_rejects() throws Exception {
        // 平台密钥已加载，但 fromPubkey 与平台公钥不一致 → 拒绝（即使夹带 prikey 参数）
        when(platformKeystore.getPrikey()).thenReturn(repeat('a', 64));
        when(platformKeystore.getPubkey()).thenReturn(repeat('b', 64));

        mockMvc.perform(post("/ClientToTransferAccount")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", repeat('c', 64))
                        .param("toPubkeyHash", "bb")
                        .param("amount", "100")
                        .param("prikey", "attacker-supplied-plaintext-private-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(5000.0))
                .andExpect(jsonPath("$.message", containsString("fromPubkey does not match the platform keystore")));
    }

    @Test
    public void testSignTransfer_callerKeystoreOverrideIgnored() throws Exception {
        // 旧缺陷：signTransfer 允许调用方传 keystoreJson+password 覆盖签名密钥。
        // 修复后：即使传入合法 keystore，也一律使用平台密钥（此处未配置 → 拒绝）。
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();
        assertNotNull(keystoreJson);
        assertFalse(keystoreJson.isEmpty());
        when(platformKeystore.getPrikey()).thenReturn(null);

        mockMvc.perform(post("/api/v1/transfers/sign")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", "aa")
                        .param("toPubkeyHash", "bb")
                        .param("amount", "100")
                        .param("keystoreJson", keystoreJson)
                        .param("password", password))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(5000.0))
                .andExpect(jsonPath("$.message", containsString("No signing key available")));
    }

    @Test
    public void testSignTransfer_happyPath_withPlatformKey() throws Exception {
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();
        String prikey = WalletUtils.obtainPrikey(keystoreJson, password);
        String pubkey = WalletUtils.keystoreToPubkey(keystoreJson, password);
        assertFalse(prikey.isEmpty());
        assertFalse(pubkey.isEmpty());

        when(platformKeystore.getPrikey()).thenReturn(prikey);
        when(platformKeystore.getPubkey()).thenReturn(pubkey);
        when(noncePool.getMaxNonce(anyString())).thenReturn(0L);
        JsonObject nonce = new JsonObject();
        nonce.addProperty("code", 2000);
        nonce.addProperty("data", 0L);
        when(nodeController.getNonce(anyString())).thenReturn(nonce);

        String toPubkeyHash = WalletUtils.pubkeyStrToPubkeyHashStr(pubkey);
        mockMvc.perform(post("/api/v1/transfers/sign")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", pubkey)
                        .param("toPubkeyHash", toPubkeyHash)
                        .param("amount", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000))
                .andExpect(jsonPath("$.data").isNotEmpty());

        verify(noncePool).add(anyString(), any(NonceState.class));
    }

    @Test
    public void testClientToTransferAccount_happyPath_withPlatformKey() throws Exception {
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();
        String prikey = WalletUtils.obtainPrikey(keystoreJson, password);
        String pubkey = WalletUtils.keystoreToPubkey(keystoreJson, password);

        when(platformKeystore.getPrikey()).thenReturn(prikey);
        when(platformKeystore.getPubkey()).thenReturn(pubkey);
        when(noncePool.getMaxNonce(anyString())).thenReturn(0L);
        JsonObject nonce = new JsonObject();
        nonce.addProperty("code", 2000);
        nonce.addProperty("data", 0L);
        when(nodeController.getNonce(anyString())).thenReturn(nonce);

        String toPubkeyHash = WalletUtils.pubkeyStrToPubkeyHashStr(pubkey);
        mockMvc.perform(post("/ClientToTransferAccount")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("fromPubkey", pubkey)
                        .param("toPubkeyHash", toPubkeyHash)
                        .param("amount", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000))
                .andExpect(jsonPath("$.data").isNotEmpty());

        verify(noncePool).add(anyString(), any(NonceState.class));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}