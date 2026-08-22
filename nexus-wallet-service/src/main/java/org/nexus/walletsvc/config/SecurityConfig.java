package org.nexus.walletsvc.config;

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
 * wallet-service Spring Security 配置（P0-3 安全加固）。
 *
 * <p>背景：原 {@code WalletController} 所有端点零鉴权，且
 * {@code /withdrawal/approve} 的 approverId 由请求参数自报，
 * 存在审批人伪造身份的安全风险。本配置引入 JWT Bearer 鉴权 +
 * 方法级 {@code @PreAuthorize}，对敏感端点强制认证与角色校验，
 * approverId 改从 {@link SecurityContextHolder} 认证上下文获取。</p>
 *
 * <h3>鉴权模型</h3>
 * <ul>
 *   <li>无状态 JWT（HS256 自对称密钥），密钥从 {@code nexus.security.jwt.secret}
 *       读取，生产环境通过 {@code JWT_SECRET} 环境变量注入，与 signing-service
 *       共享同一密钥实现服务间 token 互信</li>
 *   <li>{@link JwtAuthenticationFilter} 在 UsernamePasswordAuthenticationFilter
 *       之前执行，解析 Bearer token 填充 SecurityContext</li>
 *   <li>{@link #securityFilterChain} 配置：
 *       <ul>
 *         <li>CSRF 关闭（无状态 REST API，无 cookie 会话）</li>
 *         <li>sessionCreationPolicy=STATELESS（不创建 HTTP Session）</li>
 *         <li>actuator health/info/prometheus 公开放行（k8s 探针 / Prometheus 拉取）</li>
 *         <li>{@code /api/v1/wallet/health} 公开放行（健康检查）</li>
 *         <li>其余请求 authenticated()（由 @PreAuthorize 细化角色权限）</li>
 *       </ul>
 *     </li>
 *   <li>{@link EnableMethodSecurity} 启用 {@code @PreAuthorize} 方法级鉴权，
 *       Controller 通过 {@code @PreAuthorize("hasRole('OPERATOR')")} 等注解
 *       细化端点权限，角色常量集中定义在 {@link SecurityRoles}</li>
 * </ul>
 *
 * <h3>角色分级矩阵</h3>
 * <table>
 *   <caption>表：端点 → 角色对照表</caption>
 *   <tr><th>角色</th><th>端点</th></tr>
 *   <tr><td>OPERATOR</td><td>/whitelist/check、/withdrawal/request、
 *       /withdrawal/execute、/custody/balance</td></tr>
 *   <tr><td>ADMIN</td><td>/whitelist/add、/whitelist/remove、/custody/rebalance</td></tr>
 *   <tr><td>APPROVER</td><td>/withdrawal/approve、/withdrawal/reject</td></tr>
 * </table>
 *
 * <h3>调用方约定</h3>
 * <p>gateway 通过 Feign 调用 wallet-service 时，需在请求头注入
 * {@code Authorization: Bearer <jwt>}，token 由 gateway 侧
 * {@code FeignJwtRequestInterceptor} 自动生成（服务间专用 token，subject
 * 为 {@code nexus-gateway}，roles 含 {@code OPERATOR}）。
 * 密钥需与 wallet-service 共享（同一 {@code JWT_SECRET} 环境变量）。
 * {@code ADMIN} 与 {@code APPROVER} 角色不签发给 gateway，分别由专用离线流程
 * 签发短期 token 供运维 / 审批人使用。</p>
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

    /**
     * SecurityFilterChain 配置。
     *
     * <p>CSRF 关闭的安全说明：wallet-service 是无状态 REST API，使用 JWT Bearer
     * token 鉴权（Authorization 头），不使用 cookie 会话，CSRF 攻击向量不适用。
     * sessionCreationPolicy=STATELESS 进一步确保不创建 HTTP Session。</p>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            log.error("nexus.security.jwt.secret 未配置，wallet-service 将以 fail-closed 模式启动："
                    + "所有受保护端点都会拒绝请求。请通过 JWT_SECRET 环境变量注入密钥。");
        }
        http
                // 无状态 REST API：关闭 CSRF，不创建 HTTP Session
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 公开端点：actuator health/info/prometheus（k8s 探针 / Prometheus 拉取）
                // + 钱包服务健康检查 /api/v1/wallet/health
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus",
                                "/api/v1/wallet/health")
                        .permitAll()
                        // 其余所有请求需认证（由 @PreAuthorize 细化角色权限）
                        .anyRequest().authenticated())
                // JWT 过滤器置于 UsernamePasswordAuthenticationFilter 之前
                .addFilterBefore(jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}