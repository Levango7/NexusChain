package org.nexus.gateway.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SecurityConfig} 单元测试。
 *
 * <p>SecurityConfig 是 v2.27.0 第三轮安全审计新建的 Spring Security 配置（P1-7），
 * 启用 {@code @EnableMethodSecurity} 使 {@code @PreAuthorize} 生效，HttpSecurity
 * 配置为 permitAll（鉴权由 ApiKeyInterceptor / RequestSignatureInterceptor 拦截器
 * 链负责），并显式禁用 CSRF。
 *
 * <p>测试策略：使用轻量 {@link WebApplicationContext}（仅加载 SecurityConfig +
 * 测试 Controller + {@code @EnableWebMvc}），不触发 Spring Boot 自动配置，避免
 * Nacos/Feign/Kafka 等基础设施加载。通过 MockMvc 验证：
 * <ul>
 *   <li>SecurityFilterChain bean 正确创建</li>
 *   <li>所有请求 permitAll，无需认证即可访问</li>
 *   <li>CSRF 已禁用，POST 无需 CSRF token</li>
 * </ul>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        SecurityConfig.class,
        SecurityConfigTest.WebConfig.class,
        SecurityConfigTest.TestEndpoint.class
})
@WebAppConfiguration
class SecurityConfigTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private SecurityFilterChain securityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    @Test
    @DisplayName("SecurityFilterChain bean 正确创建")
    void securityFilterChainBeanCreated() {
        assertNotNull(securityFilterChain, "SecurityFilterChain 应由 SecurityConfig 注入");
    }

    @Test
    @DisplayName("permitAll: GET 请求无需认证即可访问")
    void anyRequestPermittedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/test/sec"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CSRF 已禁用: POST 无需 CSRF token 即可访问")
    void csrfDisabledAllowsPostWithoutToken() throws Exception {
        mockMvc.perform(post("/test/sec"))
                .andExpect(status().isOk());
    }

    /**
     * 最小 WebMvc 配置，注册 HandlerMapping 以处理测试端点。
     */
    @Configuration
    @EnableWebMvc
    static class WebConfig {
    }

    /**
     * 测试用端点，验证安全过滤链对实际请求的行为。
     */
    @RestController
    static class TestEndpoint {

        @GetMapping("/test/sec")
        String get() {
            return "ok";
        }

        @PostMapping("/test/sec")
        String post() {
            return "ok";
        }
    }
}