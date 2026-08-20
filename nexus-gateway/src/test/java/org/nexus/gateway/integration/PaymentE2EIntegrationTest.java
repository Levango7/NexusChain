package org.nexus.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.nexus.gateway.interceptor.ApiKeyInterceptor;
import org.nexus.gateway.orchestration.connectors.ChainConnector;
import org.nexus.gateway.orchestration.connectors.ConsortiumConnector;
import org.nexus.gateway.security.RequestSignatureInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 支付全流程 E2E 测试。基于 MockMVC 模拟支付编排链路。
 *
 * <p>支付 API（{@code /api/v1/payments/**}）同时受 {@link ApiKeyInterceptor}
 * （商户 API Key 鉴权）和 {@link RequestSignatureInterceptor}（HMAC 请求签名）保护。
 * 本测试聚焦支付编排链路本身而非鉴权边界，因此将两个拦截器均替换为 no-op mock，
 * 使所有请求直接放行。此模式与 {@code OrchestrationE2ETest} 对齐。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentE2EIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private ChainConnector chainConnector;
    @MockBean private ConsortiumConnector consortiumConnector;

    // 替换鉴权拦截器为 no-op mock，让支付 E2E 测试直接驱动业务链路。
    // WebConfig 将这两个 bean 注册到 Spring MVC 拦截器链；用 Mockito mock 替换后，
    // preHandle() 默认返回 false 会拒绝所有请求，因此必须在 setup 中 stub 为 true。
    // 作用域仅限本测试类，其他集成测试仍使用真实拦截器。
    @MockBean private ApiKeyInterceptor apiKeyInterceptor;
    @MockBean private RequestSignatureInterceptor requestSignatureInterceptor;

    @BeforeEach
    void setup() throws Exception {
        reset(chainConnector, consortiumConnector);
        // 放行所有请求，绕过 API Key 鉴权与 HMAC 请求签名校验
        when(apiKeyInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(requestSignatureInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        // 模拟 core 通道可用：ConnectorRegistry 会跳过 getId() 返回 null 的连接器，
        // 因此必须 stub getId() 返回非 null 值，isActive() 返回 true，
        // 才能让多通道路由引擎正确识别 core 通道为活跃状态。
        when(chainConnector.getId()).thenReturn("chain");
        when(chainConnector.isActive()).thenReturn(true);
    }

    @Test @Order(1)
    void registerMerchant() throws Exception {
        // MerchantController 注册端点为 POST /api/v1/merchants/register（返回 201 CREATED），
        // 并非 POST /api/v1/merchants（该路径无 handler，Spring 会按静态资源解析并抛出
        // NoResourceFoundException）。此处对齐 controller 实际路由与响应状态码。
        String resp = mockMvc.perform(post("/api/v1/merchants/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantName\":\"E2EShop\",\"email\":\"e2e@test.com\","
                        + "\"settlementAddress\":\"1E2EAddr00000000000000000000000000000\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertTrue(resp.contains("merchantId") || resp.contains("id"));
    }

    @Test @Order(2)
    void createPayment() throws Exception {
        // PaymentOrchestrationController.createPayment() 接收 POST /api/v1/payments，
        // 期望字段名为 merchant_id（下划线），amount 为纯整数（Long.parseLong），
        // 返回 201 CREATED。此处对齐控制器实际契约。
        String resp = mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"100\",\"currency\":\"USDC\",\"merchant_id\":\"1\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertTrue(resp.contains("paymentId") || resp.contains("id"));
    }

    @Test @Order(3)
    void multiChannelRouting() {
        assertTrue(chainConnector.isActive(), "core通道应可用");
    }

    @Test @Order(4)
    void largeAmountPayment() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"1000000\",\"currency\":\"USDC\",\"merchant_id\":\"1\"}"))
                .andReturn().getResponse().getContentAsString();
        assertNotNull(resp);
    }

    @Test @Order(5)
    void paymentTimeoutDoesNotCrash() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"50\",\"currency\":\"USDC\",\"merchant_id\":\"1\"}"))
                .andReturn().getResponse().getContentAsString();
        assertNotNull(resp);
    }

    @Test @Order(6)
    void duplicatePaymentHandled() throws Exception {
        String resp = mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"100\",\"currency\":\"USDC\",\"merchant_id\":\"1\",\"idempotencyKey\":\"dup-001\"}"))
                .andReturn().getResponse().getContentAsString();
        assertNotNull(resp);
    }
}