package org.nexus.gateway.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.nexus.gateway.client.ExchangeWalletClient;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.nexus.sdk.wallet.WalletUtils;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gateway → Core cross-module integration test.
 * Tests the full payment flow WITHOUT skip-confirmation,
 * simulating real chain confirmation via the mock Core RPC.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@WithMockUser(username = "admin", roles = {"ADMIN", "OPERATOR"})
class GatewayCoreIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // PaymentServiceImpl 直接注入 Feign 客户端（Phase 1 #55 改造），
    // ExchangeWalletClient 兼容层默认不装配（@ConditionalOnProperty enabled=false），
    // 因此必须 mock Feign 客户端才能让退款流程在无远程服务的测试环境正常工作。
    @MockBean
    private ExchangeWalletClient walletClient;

    @MockBean
    private SigningServiceFeignClient signingServiceFeignClient;

    @MockBean
    private WalletMgmtFeignClient walletMgmtFeignClient;

    /**
     * 真实合法的 Base58Check 地址（由 20 字节 pubkey hash 经 WalletUtils 构造）。
     *
     * <p>不再使用 Mockito.mockStatic 模拟 {@link WalletUtils#addressToPubkeyHash}：
     * MockedStatic 仅在创建线程生效（thread-local），而退款阶段2 经
     * ThreePhaseExecutionTemplate 在 ForkJoinPool 异步线程执行真实转换，
     * mock 不生效导致地址转换返回 null，退款被误判为 FAILED。
     * 该方法本身是纯函数（Base58 解码 + 截取 + Hex），无需 mock。</p>
     */
    private static final String MERCHANT_SETTLEMENT_ADDRESS =
            WalletUtils.pubkeyHashToAddress("aabbccddeeff00112233445566778899aabbccdd");
    private static final String PAYER_ADDRESS =
            WalletUtils.pubkeyHashToAddress("1122334455667788990011223344556677889900");

    @BeforeEach
    void stubWalletSign() {
        // Refund signing is delegated to signing-service via signTransfer (platform key).
        // In this gateway-only integration test the wallet service is stubbed to succeed.
        when(signingServiceFeignClient.signTransfer(anyString(), anyString(), org.mockito.ArgumentMatchers.any(java.math.BigDecimal.class)))
                .thenReturn("0xRefundTxHash1234567890abcdef1234567890abcdef");
        // Refund flow converts the payer address via WalletUtils.addressToPubkeyHash (pure function,
        // executed on the async ForkJoinPool thread inside the three-phase template — no mock needed).
    }

    private static String apiKey;
    private static Long merchantId;
    private static Long orderId;

    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("Setup: register + verify merchant + generate API key")
    void setupMerchant() throws Exception {
        // Register
        MvcResult reg = mockMvc.perform(post("/api/v1/merchants/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantName\":\"Integration Shop\",\"email\":\"int@test.io\",\"settlementAddress\":\"" + MERCHANT_SETTLEMENT_ADDRESS + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = reg.getResponse().getContentAsString();
        merchantId = Long.parseLong(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // Verify
        mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"VERIFIED\"}"))
                .andExpect(status().isOk());

        // Generate API key
        MvcResult keyRes = mockMvc.perform(post("/api/v1/merchants/" + merchantId + "/api-keys")
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andReturn();
        apiKey = keyRes.getResponse().getContentAsString().replaceAll(".*\"apiKey\":\"([^\"]+)\".*", "$1");
        assertNotNull(apiKey);
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("Create order and initiate payment")
    void createOrderAndPay() throws Exception {
        // Create order
        MvcResult orderRes = mockMvc.perform(post("/api/v1/orders")
                .header("X-NexusChain-ApiKey", apiKey)
                .with(SignedRequests.sandbox())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantId\":\"" + merchantId + "\",\"amount\":100000,\"description\":\"Integration test order\",\"notifyUrl\":\"http://localhost:9999/cb\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String orderBody = orderRes.getResponse().getContentAsString();
        orderId = Long.parseLong(orderBody.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // Initiate payment
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/pay")
                .header("X-NexusChain-ApiKey", apiKey)
                .with(SignedRequests.sandbox())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payerAddress\":\"" + PAYER_ADDRESS + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("Confirm payment via chain RPC (non-skip mode)")
    void confirmPaymentViaChain() throws Exception {
        // In sandbox mode, the ChainRpcClient calls the mock Core RPC at localhost:3000
        // which always returns confirmed. This tests the real HTTP call path.
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm")
                .header("X-NexusChain-ApiKey", apiKey)
                .with(SignedRequests.sandbox())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chainTxHash\":\"abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.chainTxHash").value("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"));
    }

    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("Reject duplicate payment confirmation")
    void rejectDuplicateConfirm() throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm")
                .header("X-NexusChain-ApiKey", apiKey)
                .with(SignedRequests.sandbox())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chainTxHash\":\"abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("Refund a paid order")
    void refundPaidOrder() throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund")
                .header("X-NexusChain-ApiKey", apiKey)
                .with(SignedRequests.sandbox())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":50000,\"reason\":\"Customer request\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("Reject unauthorized access (no API key)")
    void rejectUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/orders/" + orderId))
                .andExpect(status().isUnauthorized());
    }
}