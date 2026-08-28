package org.nexus.bridge;

import com.jayway.jsonpath.JsonPath;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * Bridge full-chain integration test: lock → mint → burn → unlock.
 * Tests the complete cross-chain asset lifecycle via the actual REST API contract.
 *
 * <p><b>2026-08-06（P1 多签验签修复）</b>：MINT/UNLOCK 的签名改为真实
 * Ed25519 签名——测试用固定的测试密钥对签名，载荷与
 * {@link BridgeValidator#buildPayload} 一致（timestamp 缺省为 0），
 * 签名者 ID 即白名单中的公钥十六进制（见 application-test.yml 的
 * {@code nexus.bridge.validator-public-keys}）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)

class BridgeFullChainIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // Spring Boot 4.0.8 升级修复：测试上下文未启用 tracing autoconfigure，
    // BridgeServiceImpl 构造函数需要 Tracer bean，用 @MockitoBean 提供 mock。
    @MockitoBean
    private Tracer tracer;

    private static String lockTxId;
    private static String burnTxId;

    // ===== 固定测试密钥对（仅测试用；与 application-test.yml 白名单对应） =====
    private static final String PUB_V1 =
            "302a300506032b65700321004d0fe4ccc01fcf50880797f51265c843b096b85e65e2003f6ada9c41f6121f9f";
    private static final String PRIV_V1 =
            "302e020100300506032b657004220420fd4cbb12bacf85fd823dfac7345bf5136171044213edf0557f7b852e4de03dd0";
    private static final String PUB_V2 =
            "302a300506032b6570032100cddc01e2bc06777cfabde2409c44a8b1f01396074aed624bada5e235f57e3653";
    private static final String PRIV_V2 =
            "302e020100300506032b6570042204203665e6554a962618bf7fc03a7a5f9407e932a26f29a23a42a9b03ff97bb3f34b";

    private static final String LOCK_BODY =
            "{\"sourceChainId\":\"nexus\",\"targetChainId\":\"ethereum\","
                    + "\"amount\":500000,\"userAddress\":\"0xUser\","
                    + "\"targetAddress\":\"0xRecipient\",\"sourceTxHash\":\"0xLockHash\"}";

    /** 为 lockTxId 的 MINT 生成两个验证者的真实签名 JSON 片段。 */
    private static String twoSigJson(String lockTxId, long amount, String targetAddress) throws Exception {
        String payload = BridgeValidator.buildPayload("nexus", lockTxId, amount, targetAddress, 0L);
        return "\"signatures\":{"
                + "\"" + PUB_V1 + "\":\"" + sign(PRIV_V1, payload) + "\","
                + "\"" + PUB_V2 + "\":\"" + sign(PRIV_V2, payload) + "\"}";
    }

    /** 为 burnTxId 的 UNLOCK 生成两个验证者的真实签名 JSON 片段（载荷链为销毁链 ethereum）。 */
    private static String twoUnlockSigJson(String burnTxId, long amount, String targetAddress) throws Exception {
        String payload = BridgeValidator.buildPayload("ethereum", burnTxId, amount, targetAddress, 0L);
        return "\"signatures\":{"
                + "\"" + PUB_V1 + "\":\"" + sign(PRIV_V1, payload) + "\","
                + "\"" + PUB_V2 + "\":\"" + sign(PRIV_V2, payload) + "\"}";
    }

    private static String sign(String privateKeyHex, String payload) throws Exception {
        byte[] keyBytes = hexToBytes(privateKeyHex);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(keyBytes)));
        sig.update(payload.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(sig.sign());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }

    @Test
    @Order(1)
    @DisplayName("Lock assets on source chain")
    void lockAssets() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/bridge/lock")
                        .with(user("test").roles("OPERATOR", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(LOCK_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.operationType").value("BRIDGE_LOCK"))
                .andExpect(jsonPath("$.sourceChainId").value("nexus"))
                .andExpect(jsonPath("$.amount").value(500000))
                .andReturn();
        lockTxId = JsonPath.read(res.getResponse().getContentAsString(), "$.txId");
        assertNotNull(lockTxId, "txId should be returned");
    }

    @Test
    @Order(2)
    @DisplayName("Mint wrapped assets on target chain with valid threshold signatures")
    void mintWrapped() throws Exception {
        mockMvc.perform(post("/api/v1/bridge/mint")
                        .with(user("test").roles("OPERATOR", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockTxId\":\"" + lockTxId + "\","
                                + twoSigJson(lockTxId, 500000L, "0xRecipient")
                                + ",\"minterAddress\":\"" + PUB_V1 + "\",\"targetChainId\":\"ethereum\",\"timestamp\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MINTED"));
    }

    @Test
    @Order(3)
    @DisplayName("Burn wrapped assets (initiate return)")
    void burnWrapped() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/bridge/burn")
                        .with(user("test").roles("OPERATOR", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceChainId\":\"nexus\",\"targetChainId\":\"ethereum\","
                                + "\"amount\":500000,\"userAddress\":\"0xUser\","
                                + "\"targetAddress\":\"0xRecipient\",\"sourceTxHash\":\"0xBurnHash\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("BURNED"))
                .andExpect(jsonPath("$.operationType").value("BRIDGE_BURN"))
                .andReturn();
        burnTxId = JsonPath.read(res.getResponse().getContentAsString(), "$.txId");
        assertNotNull(burnTxId, "burn txId should be returned");
    }

    @Test
    @Order(4)
    @DisplayName("Unlock original assets on source chain")
    void unlockAssets() throws Exception {
        mockMvc.perform(post("/api/v1/bridge/unlock")
                        .with(user("test").roles("OPERATOR", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"burnTxId\":\"" + burnTxId + "\","
                                + twoUnlockSigJson(burnTxId, 500000L, "0xRecipient")
                                + ",\"unlockerAddress\":\"" + PUB_V1 + "\",\"sourceChainId\":\"nexus\",\"timestamp\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNLOCKED"));
    }

    @Test
    @Order(5)
    @DisplayName("Query bridge transaction by txId")
    void queryTx() throws Exception {
        mockMvc.perform(get("/api/v1/bridge/tx/" + lockTxId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MINTED"))
                .andExpect(jsonPath("$.sourceChainId").value("nexus"))
                .andExpect(jsonPath("$.targetChainId").value("ethereum"))
                .andExpect(jsonPath("$.amount").value(500000));
    }

    @Test
    @Order(6)
    @DisplayName("Reject mint with insufficient signatures (below threshold)")
    void rejectMintWithInsufficientSignatures() throws Exception {
        MvcResult lockRes = mockMvc.perform(post("/api/v1/bridge/lock")
                        .with(user("test").roles("OPERATOR", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceChainId\":\"nexus\",\"targetChainId\":\"ethereum\","
                                + "\"amount\":100000,\"userAddress\":\"0xUser2\","
                                + "\"targetAddress\":\"0xRecipient2\",\"sourceTxHash\":\"0xLockHash2\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String txId = JsonPath.read(lockRes.getResponse().getContentAsString(), "$.txId");

        // 只有 1 个真实有效签名（阈值 2）
        String payload = BridgeValidator.buildPayload("nexus", txId, 100000L, "0xRecipient2", 0L);
        String oneSig = "\"signatures\":{\"" + PUB_V1 + "\":\"" + sign(PRIV_V1, payload) + "\"}";

        mockMvc.perform(post("/api/v1/bridge/mint")
                        .with(user("test").roles("OPERATOR", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockTxId\":\"" + txId + "\","
                                + oneSig
                                + ",\"minterAddress\":\"" + PUB_V1 + "\",\"targetChainId\":\"ethereum\",\"timestamp\":0}"))
                .andExpect(status().isConflict());

        // 修复点 2：失败的锁定交易应进入 FAILED 终态并记录失败原因
        mockMvc.perform(get("/api/v1/bridge/tx/" + txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.containsString("Insufficient")));
    }

    @Test
    @Order(7)
    @DisplayName("Reject mint with forged signature content even above count threshold (P1 fix)")
    void rejectMintWithForgedSignatures() throws Exception {
        MvcResult lockRes = mockMvc.perform(post("/api/v1/bridge/lock")
                        .with(user("test").roles("OPERATOR", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceChainId\":\"nexus\",\"targetChainId\":\"ethereum\","
                                + "\"amount\":100000,\"userAddress\":\"0xUser3\","
                                + "\"targetAddress\":\"0xRecipient3\",\"sourceTxHash\":\"0xLockHash3\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String txId = JsonPath.read(lockRes.getResponse().getContentAsString(), "$.txId");

        // 数量达到阈值（2 个），但签名内容伪造 —— 修复前会通过
        String forged = "\"signatures\":{\"" + PUB_V1 + "\":\"deadbeef\",\"" + PUB_V2 + "\":\"cafebabe\"}";

        mockMvc.perform(post("/api/v1/bridge/mint")
                        .with(user("test").roles("OPERATOR", "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lockTxId\":\"" + txId + "\","
                                + forged
                                + ",\"minterAddress\":\"" + PUB_V1 + "\",\"targetChainId\":\"ethereum\",\"timestamp\":0}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/v1/bridge/tx/" + txId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    @Order(8)
    @DisplayName("Bridge status reports active state and pending count")
    void bridgeStatus() throws Exception {
        mockMvc.perform(get("/api/v1/bridge/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.signatureThreshold").value(2));
    }
}
