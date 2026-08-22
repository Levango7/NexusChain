package org.nexus.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Gateway Spring Security 配置。
 *
 * <p>启用 {@code @EnableMethodSecurity} 使 {@code @PreAuthorize} 注解生效
 * （MerchantController 写端点要求 hasRole('ADMIN')）。
 * HttpSecurity 配置为 permitAll，鉴权由 {@link ApiKeyInterceptor} 和
 * {@link RequestSignatureInterceptor} 拦截器链负责。
 * CSRF 对 REST API 无意义，显式禁用。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}