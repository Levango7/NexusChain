package org.nexus.gateway.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import io.micrometer.tracing.Tracer;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商户管理端点 JWT 鉴权闭环测试（2026-09-03 死端点回归修复）。
 *
 * <p>背景：S1 审计修复给管理端点补 {@code @PreAuthorize("hasRole('ADMIN')")}
 * 后，gateway 无认证组件产生 ROLE_ADMIN → 所有 ADMIN 端点对所有人 403
 * （含合法管理员）。既有集成测试全部用 {@code @WithMockUser} 绕过真实
 * 过滤器链，无法暴露该回归。</p>
 *
 * <p>本测试<b>不使用</b> {@code @WithMockUser}，签发真实 JWT 经
 * {@code Authorization: Bearer} 头走完整 Spring Security 过滤器链 +
 * {@link JwtAuthenticationFilter}，验证认证闭环：
 * <ul>
 *   <li>无认证（匿名）→ 403（安全底线保持）</li>
 *   <li>ADMIN 角色 JWT → 通过 @PreAuthorize（死端点解除）</li>
 *   <li>非 ADMIN 角色 JWT → 403（最小权限）</li>
 *   <li>非法 token → 403（fail-closed）</li>
 * </ul></p>
 *
 * <p>目标端点选取 v1 {@code POST /api/v1/merchants/{id}/api-keys}
 * （{@code @PreAuthorize("hasRole('ADMIN')")} 的管理写端点；
 * {@code /register} 为商户自注册公开端点，不在此列）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@DisplayName("商户管理端点 JWT 鉴权闭环（死端点修复验证）")
class MerchantAdminJwtAuthIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    // Spring Boot 4.0.8 升级修复：测试上下文未启用 tracing autoconfigure，
    // PaymentServiceImpl 等构造函数需要 Tracer bean，用 @MockitoBean 提供 mock。
    @MockitoBean
    private Tracer tracer;

    /** 目标端点：v1 商户 API Key 生成（带 @PreAuthorize("hasRole('ADMIN')") 的管理写端点） */
    private static final String ADMIN_ENDPOINT = "/api/v1/merchants/1/api-keys";

    @Test
    @DisplayName("无认证（匿名）→ 403：安全底线保持")
    void anonymousRequest_shouldBeForbidden() throws Exception {
        mockMvc.perform(post(ADMIN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN 角色 JWT → 通过 @PreAuthorize：死端点解除")
    void adminJwt_shouldPassMethodSecurity() throws Exception {
        String token = tokenProvider.generateToken("admin-op",
                List.of("ADMIN"), 60_000L);

        mockMvc.perform(post(ADMIN_ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                // 鉴权已通过的判据：不是 403。越过 @PreAuthorize 后：
                // 商户 1 不存在 → 404；商户已 verify → 可能 409/500；
                // 绝不能是 403 鉴权失败。
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertNotEquals(403,
                        result.getResponse().getStatus(),
                        "ADMIN JWT 不应被 @PreAuthorize 拒绝（死端点回归重现）"));
    }

    @Test
    @DisplayName("非 ADMIN 角色 JWT → 403：最小权限")
    void nonAdminJwt_shouldBeForbidden() throws Exception {
        String token = tokenProvider.generateToken("merchant-user",
                List.of("MERCHANT"), 60_000L);

        mockMvc.perform(post(ADMIN_ENDPOINT)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("非法 token → 403：fail-closed")
    void malformedJwt_shouldBeForbidden() throws Exception {
        mockMvc.perform(post(ADMIN_ENDPOINT)
                        .header("Authorization", "Bearer invalid.jwt.token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }
}