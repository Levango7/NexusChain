package org.nexus.gateway.config;

import org.nexus.gateway.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Gateway Spring Security 配置。
 *
 * <p>启用 {@code @EnableMethodSecurity} 使 {@code @PreAuthorize} 注解生效
 * （MerchantController / MerchantV2Controller 写端点要求 hasRole('ADMIN')）。</p>
 *
 * <h3>鉴权模型（2026-09-03 死端点回归修复）</h3>
 * <p>背景：S1 审计修复为商户管理端点补 {@code @PreAuthorize("hasRole('ADMIN')")}
 * 后，gateway 缺少产生 {@code ROLE_ADMIN} 的认证组件——SecurityContext 恒为
 * 匿名，ADMIN 端点对所有人（含合法管理员）403，成为死端点。</p>
 *
 * <p>现架构（双层鉴权，职责分离）：
 * <ul>
 *   <li><b>商户支付 API</b>（订单/支付/webhook 等）：HttpSecurity 层
 *       {@code permitAll} 放行到 MVC 层，由 {@code ApiKeyInterceptor} /
 *       {@code RequestSignatureInterceptor} 拦截器链按商户 API Key 鉴权
 *       （既有行为不变，本修复不触碰）</li>
 *   <li><b>管理端点</b>（verify / api-keys / revoke 等）：方法安全层
 *       {@code @PreAuthorize} + 本配置接入的 {@link JwtAuthenticationFilter}
 *       提供 ROLE_ADMIN 认证来源——管理员携带
 *       {@code Authorization: Bearer <jwt>}（roles 含 ADMIN）访问即可通过</li>
 * </ul></p>
 *
 * <p>JWT 过滤器置于 UsernamePasswordAuthenticationFilter 之前，无 Bearer 头
 * 或非法 token 时静默放行（保持匿名），受保护端点由方法安全层 403 拒绝；
 * SESSIONLESS：JWT 无状态鉴权不创建 HTTP Session。CSRF 对 Bearer token /
 * API Key 的 REST API 无意义，显式禁用。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 商户支付 API 在拦截器层鉴权（ApiKey/签名），HttpSecurity 层放行；
            // 管理端点由方法安全层 @PreAuthorize + JWT 过滤器保护
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            // JWT 过滤器：为 @PreAuthorize 方法安全层提供认证来源（死端点修复核心）
            .addFilterBefore(jwtAuthenticationFilter,
                    UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}