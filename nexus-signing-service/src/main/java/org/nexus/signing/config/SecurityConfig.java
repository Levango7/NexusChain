package org.nexus.signing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * signing-service Spring Security 配置（P1-F1 安全加固）。
 *
 * <p>背景：原 {@code TxController#signTransfer} 端点零鉴权（仅 platformPubkey
 * 字段校验），{@code WalletController#obtainPrikey} 可公开获取明文私钥。本配置
 * 引入 JWT Bearer 鉴权 + 方法级 {@code @PreAuthorize}，对 {@code /api/**}
 * 强制认证，对 {@code /obtainPrikey} 强制 {@code ROLE_ADMIN}。</p>
 *
 * <h3>鉴权模型</h3>
 * <ul>
 *   <li>无状态 JWT（HS256 自对称密钥），密钥从 {@code nexus.security.jwt.secret}
 *       读取，生产环境通过 {@code JWT_SECRET} 环境变量注入</li>
 *   <li>{@link JwtAuthenticationFilter} 在 UsernamePasswordAuthenticationFilter
 *       之前执行，解析 Bearer token 填充 SecurityContext</li>
 *   <li>{@link #securityFilterChain} 配置：
 *       <ul>
 *         <li>CSRF 关闭（无状态 REST API，无 cookie 会话）</li>
 *         <li>sessionCreationPolicy=STATELESS（不创建 HTTP Session）</li>
 *         <li>actuator health/info/prometheus 公开放行（k8s 探针 / Prometheus 拉取）</li>
 *         <li>{@code /api/**} 与 {@code /obtainPrikey} 等敏感端点 authenticated()</li>
 *         <li>其余请求 authenticated()（白名单仅 actuator 公开端点）</li>
 *       </ul>
 *     </li>
 *   <li>{@link EnableMethodSecurity} 启用 {@code @PreAuthorize} 方法级鉴权，
 *       Controller 通过 {@code @PreAuthorize("hasRole('SIGNING_SERVICE')")} 等注解
 *       细化端点权限</li>
 * </ul>
 *
 * <h3>调用方约定</h3>
 * <p>gateway 通过 Feign 调用 signing-service 时，需在请求头注入
 * {@code Authorization: Bearer <jwt>}，token 由 gateway 侧
 * {@code FeignJwtRequestInterceptor} 自动生成（服务间专用 token，subject
 * 为 {@code nexus-gateway}，roles 含 {@code SIGNING_SERVICE}）。密钥需与
 * signing-service 共享（同一 {@code JWT_SECRET} 环境变量）。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${nexus.security.jwt.secret:}")
    private String jwtSecret;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            log.error("nexus.security.jwt.secret 未配置，signing-service 将以 fail-closed 模式启动："
                    + "所有 /api/** 端点都会拒绝请求。请通过 JWT_SECRET 环境变量注入密钥。");
        }
        http
                // 无状态 REST API：关闭 CSRF，不创建 HTTP Session
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 公开端点：actuator health/info/prometheus（k8s 探针 / Prometheus 拉取）
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus")
                        .permitAll()
                        // 其余所有请求（含 /api/** 与 /obtainPrikey 等敏感端点）需认证
                        .anyRequest().authenticated())
                // JWT 过滤器置于 UsernamePasswordAuthenticationFilter 之前
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}