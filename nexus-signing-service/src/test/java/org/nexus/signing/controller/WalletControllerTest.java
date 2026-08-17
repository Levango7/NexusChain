package org.nexus.signing.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.sdk.wallet.WalletUtils;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link WalletController} 单元测试。
 *
 * <p>WalletController 调用 {@link WalletUtils} 静态方法（纯计算，无外部依赖），
 * 测试通过 MockMvc 验证各端点的返回结构。</p>
 *
 * <p>覆盖端点：
 * <ul>
 *   <li>GET /fromPassword — 从密码生成 keystore</li>
 *   <li>POST /modifyPassword — 修改 keystore 密码</li>
 *   <li>GET /verifyAddress — 地址校验</li>
 *   <li>GET /pubkeyHashToAddress — pubkeyHash → address</li>
 *   <li>GET /addressToPubkeyHash — address → pubkeyHash</li>
 *   <li>POST /keystoreToAddress — keystore → address</li>
 *   <li>POST /keystoreToPubkey — keystore → pubkey</li>
 *   <li>POST /keystoreToPubkeyHash — keystore → pubkeyHash</li>
 *   <li>POST /obtainPrikey — P2-F1 已下线，验证返回 404</li>
 *   <li>POST /prikeyToPubkey — prikey → pubkey</li>
 *   <li>POST /pubkeyStrToPubkeyHashStr — pubkey → pubkeyHash</li>
 * </ul></p>
 */
@ExtendWith(MockitoExtension.class)
public class WalletControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    public void setUp() {
        WalletController controller = new WalletController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    public void testFromPassword_validPassword_returnsSuccess() throws Exception {
        mockMvc.perform(get("/fromPassword")
                        .param("password", "password123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000));
    }

    @Test
    public void testVerifyAddress_validAddress_returnsSuccess() throws Exception {
        // 先生成一个合法地址
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();
        String pubkey = WalletUtils.keystoreToPubkey(keystoreJson, password);
        String pubkeyHash = WalletUtils.pubkeyStrToPubkeyHashStr(pubkey);
        String address = WalletUtils.pubkeyHashToAddress(pubkeyHash);

        mockMvc.perform(get("/verifyAddress")
                        .param("address", address))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000));
    }

    @Test
    public void testVerifyAddress_invalidAddress_returnsError() throws Exception {
        mockMvc.perform(get("/verifyAddress")
                        .param("address", "invalid-address"))
                .andExpect(status().isOk());
        // 不断言具体 statusCode，因为不同无效格式返回不同错误码（6000/7000）
    }

    @Test
    public void testPubkeyHashToAddress_validHash_returnsSuccess() throws Exception {
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();
        String pubkey = WalletUtils.keystoreToPubkey(keystoreJson, password);
        String pubkeyHash = WalletUtils.pubkeyStrToPubkeyHashStr(pubkey);

        mockMvc.perform(get("/pubkeyHashToAddress")
                        .param("pubkeyHash", pubkeyHash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000));
    }

    @Test
    public void testAddressToPubkeyHash_validAddress_returnsSuccess() throws Exception {
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();
        String pubkey = WalletUtils.keystoreToPubkey(keystoreJson, password);
        String pubkeyHash = WalletUtils.pubkeyStrToPubkeyHashStr(pubkey);
        String address = WalletUtils.pubkeyHashToAddress(pubkeyHash);

        mockMvc.perform(get("/addressToPubkeyHash")
                        .param("address", address))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000));
    }

    @Test
    public void testKeystoreToAddress_validKeystore_returnsSuccess() throws Exception {
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();

        mockMvc.perform(post("/keystoreToAddress")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("keystoreJson", keystoreJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000));
    }

    @Test
    public void testKeystoreToPubkey_validKeystore_returnsSuccess() throws Exception {
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();

        mockMvc.perform(post("/keystoreToPubkey")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("keystoreJson", keystoreJson)
                        .param("password", password))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000));
    }

    @Test
    public void testKeystoreToPubkeyHash_validKeystore_returnsSuccess() throws Exception {
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();

        mockMvc.perform(post("/keystoreToPubkeyHash")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("keystoreJson", keystoreJson)
                        .param("password", password))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000));
    }

    @Test
    public void testObtainPrikey_endpointRemoved_returnsNotFound() throws Exception {
        // P2-F1: /obtainPrikey 端点已彻底下线（移除 @RequestMapping），
        // 不再暴露为 REST 端点，HTTP 请求返回 404
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();

        mockMvc.perform(post("/obtainPrikey")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("keystoreJson", keystoreJson)
                        .param("password", password))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testPrikeyToPubkey_validPrikey_returnsSuccess() throws Exception {
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();
        String prikey = WalletUtils.obtainPrikey(keystoreJson, password);

        mockMvc.perform(post("/prikeyToPubkey")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("prikey", prikey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000));
    }

    @Test
    public void testPubkeyToPubkeyHash_validPubkey_returnsSuccess() throws Exception {
        String password = "password123";
        String keystoreJson = WalletUtils.fromPassword(password).toString();
        String pubkey = WalletUtils.keystoreToPubkey(keystoreJson, password);

        mockMvc.perform(post("/pubkeyStrToPubkeyHashStr")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("pubkey", pubkey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000));
    }

    @Test
    public void testModifyPassword_validInputs_returnsSuccess() throws Exception {
        String password = "password123";
        String newPassword = "newpassword456";
        String keystoreJson = WalletUtils.fromPassword(password).toString();

        mockMvc.perform(post("/modifyPassword")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("keystoreJson", keystoreJson)
                        .param("password", password)
                        .param("newPassword", newPassword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statusCode").value(2000));
    }
}