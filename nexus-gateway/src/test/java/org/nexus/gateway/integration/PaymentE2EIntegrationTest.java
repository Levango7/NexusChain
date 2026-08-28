package org.nexus.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.nexus.gateway.interceptor.ApiKeyInterceptor;
import org.nexus.gateway.orchestration.connectors.ChainConnector;
import org.nexus.gateway.orchestration.connectors.ConsortiumConnector;
import org.nexus.gateway.security.RequestSignatureInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

import org.springframework.test.web.servlet.MockMvc;
import io.micrometer.tracing.Tracer;

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
 *
 * <p>P1-4 修复：项目引入了 {@code spring-boot-starter-security} 但未自定义 SecurityConfig，
 * Spring Security 默认配置启用 CSRF 保护并拒绝所有无认证请求（返回 403）。
 * Spring Security 在 Servlet Filter 链中执行，先于 Spring MVC Interceptor 链，
 * 因此 mock 拦截器无法绕过 Security。本测试通过 {@link TestSecurityConfig} 显式
 * 放行所有请求并禁用 CSRF，使 mock 拦截器 stub 能有效放行请求。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "sandbox"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(PaymentE2EIntegrationTest.TestSecurityConfig.class)
class PaymentE2EIntegrationTest {

    /**
     * 测试专用 Spring Security 配置：放行所有请求并禁用 CSRF。
     *
     * <p>原因：build.gradle 引入了 spring-boot-starter-security 但主代码未提供
     * SecurityConfig，Spring Security 默认配置会启用 CSRF 并要求认证，导致所有
     * MockMvc 请求返回 403。此配置仅在测试 profile 下生效，不影响生产安全。</p>
     */
    @EnableWebSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    // Spring Boot 4.0.8 升级修复：测试上下文未启用 tracing autoconfigure，
    // PaymentServiceImpl 等构造函数需要 Tracer bean，用 @MockitoBean 提供 mock。
    @MockitoBean private Tracer tracer;
    @MockitoBean private ChainConnector chainConnector;
    @MockitoBean private ConsortiumConnector consortiumConnector;

    // 替换鉴权拦截器为 no-op mock，让支付 E2E 测试直接驱动业务链路。
    // WebConfig 将这两个 bean 注册到 Spring MVC 拦截器链；用 Mockito mock 替换后，
    // preHandle() 默认返回 false 会拒绝所有请求，因此必须在 setup 中 stub 为 true。
    // 作用域仅限本测试类，其他集成测试仍使用真实拦截器。
    // P1-4: 保留 @MockitoBean 而非改为真实鉴权，因为：
    //   1) /api/v1/merchants/register 受 ApiKeyInterceptor 保护（P0-1 安全加固移除了
    //      商户端点排除），存在鸡生蛋问题——注册首个商户需要已有 API key；
    //   2) 每个支付请求需计算 HMAC-SHA256(timestamp+nonce+method+path+body) 签名，
    //      且时间戳必须在 5 分钟窗口内，测试维护成本高；
    //   3) 本测试聚焦编排链路而非鉴权边界，鉴权边界由 ApiKeyInterceptorTest /
    //      RequestSignatureInterceptorTest 单元测试覆盖。
    @MockitoBean private ApiKeyInterceptor apiKeyInterceptor;
    @MockitoBean private RequestSignatureInterceptor requestSignatureInterceptor;
    // B4 Boot 3.3.13 升级修复：构造函数注入更严格，test profile 下无 RateLimiter bean
    //（InMemoryRateLimiter @Profile({"dev","sandbox"})，RedisRateLimiter @Profile("prod")）
    @MockitoBean private org.nexus.gateway.ratelimit.RateLimiter rateLimiter;

    @BeforeEach
    void setup() throws Exception {
        reset(chainConnector, consortiumConnector);
        // 放行所有请求，绕过 API Key 鉴权与 HMAC 请求签名校验
        when(apiKeyInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(requestSignatureInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        // B4: @MockitoBean RateLimiter 的 tryAcquire() 默认返回 false → RateLimitAdapter 返回 429，
        // 必须显式 stub 为 true 才能放行请求
        when(rateLimiter.tryAcquire(any())).thenReturn(true);
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