package org.nexus.bridge.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Bridge 服务安全配置（P0-6 安全加固）。
 *
 * <p>bridge 为独立部署的 Spring Boot 应用（buildJar 产出 fat jar，直接暴露 HTTP 端点），
 * 故鉴权在本服务内实现，而非 gateway FeignClient 层。
 *
 * <p>策略：
 * <ul>
 *   <li>{@code @EnableMethodSecurity} 启用方法级鉴权，使 BridgeController 上的
 *       {@code @PreAuthorize} 注解生效（lock/mint/burn/unlock 需 OPERATOR，
 *       pause/resume 需 ADMIN）。</li>
 *   <li>HTTP 层：GET /status、/tx、/tx/{txId} 为查询/健康检查端点，permitAll 公开；
 *       其余请求（含所有 POST 危险端点）需 authenticated()，再由 @PreAuthorize 做角色控制。</li>
 *   <li>CSRF 关闭：REST API 无状态，不使用 cookie session。</li>
 * </ul>
 *
 * <p>注：完整的 JWT 认证过滤器（解析 Bearer token 填充 SecurityContext/角色）应作为
 * 后续任务与 gateway 联调集成；本配置先就位鉴权框架，确保危险端点不可匿名访问。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET,
                        "/api/v1/bridge/status",
                        "/api/v1/bridge/tx",
                        "/api/v1/bridge/tx/**").permitAll()
                .anyRequest().authenticated()
            );
        return http.build();
    }
}