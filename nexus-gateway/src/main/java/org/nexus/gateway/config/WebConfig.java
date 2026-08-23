package org.nexus.gateway.config;

import org.nexus.gateway.interceptor.ApiKeyInterceptor;
import org.nexus.gateway.config.ApiVersionInterceptor;
import org.nexus.gateway.ratelimit.RateLimiter;
import org.nexus.gateway.security.RequestSignatureInterceptor;
import org.nexus.gateway.tenant.TenantApiKeyInterceptor;
import org.nexus.gateway.tenant.TenantRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Web MVC configuration: CORS, API key authentication interceptor, rate limiting.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final ApiKeyInterceptor apiKeyInterceptor;
    private final RateLimiter rateLimiter;
    private final ApiVersionInterceptor apiVersionInterceptor;
    private final RequestSignatureInterceptor requestSignatureInterceptor;
    private final TenantApiKeyInterceptor tenantApiKeyInterceptor;
    private final TenantRateLimiter tenantRateLimiter;

    /**
     * CORS allowed origins, externalized via {@code nexus.cors.allowed-origins} property
     * (REQ-15 安全加固：禁止硬编码 "*"，避免任意跨域请求)。
     * Comma-separated list of origins; default to the production explorer domain so
     * that an unset property does not silently open CORS to everyone.
     */
    @Value("${nexus.cors.allowed-origins:https://explorer.nexuschain.io}")
    private String[] corsAllowedOrigins;

    // RateLimiter (Redis-backed, prod profile) is optional: when no profile supplies one
    // (e.g. plain dev run without Redis), rate limiting is disabled rather than failing
    // startup. Ensure the Redis-backed rate limiter is wired in every profile that
    // needs throttling.
    public WebConfig(ApiKeyInterceptor apiKeyInterceptor, RateLimiter rateLimiter,
                     ApiVersionInterceptor apiVersionInterceptor,
                     RequestSignatureInterceptor requestSignatureInterceptor,
                     TenantApiKeyInterceptor tenantApiKeyInterceptor,
                     TenantRateLimiter tenantRateLimiter) {
        this.apiKeyInterceptor = apiKeyInterceptor;
        this.rateLimiter = rateLimiter;
        this.apiVersionInterceptor = apiVersionInterceptor;
        this.requestSignatureInterceptor = requestSignatureInterceptor;
        this.tenantApiKeyInterceptor = tenantApiKeyInterceptor;
        this.tenantRateLimiter = tenantRateLimiter;
        if (rateLimiter == null) {
            log.warn("No RateLimiter bean present - API rate limiting is DISABLED. "
                    + "Wire the Redis-backed RateLimiter (prod profile) to enable throttling.");
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(corsAllowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // API version negotiation (first in chain) — ApiVersionFilter 已在 Filter 链最早期
        // 完成版本解析与 Deprecation 头注入；保留旧 Interceptor 以兼容既有调用方
        registry.addInterceptor(apiVersionInterceptor)
                .addPathPatterns("/api/**");

        // Rate limiting applies to all API paths. Backed by the Redis RateLimiter
        // (the single rate-limiting implementation); falls through when absent.
        if (rateLimiter != null) {
            registry.addInterceptor(new RateLimitAdapter(rateLimiter))
                    .addPathPatterns("/api/v1/**", "/api/v2/**");
        }

        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/api/v1/**", "/api/v2/**")
                // Public paths: no auth required
                // P0-1 安全加固：移除 /api/v{1,2}/merchants/** 排除，商户管理端点
                // 必须受 API key 拦截器保护；写端点另加 @PreAuthorize("hasRole('ADMIN')")
                // 例外：register 是入驻入口，保留公开（否则首个商户无法引导）；
                // verify / api-keys 等管理端点仍需鉴权。
                .excludePathPatterns(
                        "/api/v1/checkout/**",       // Cashier page APIs (payer-facing)
                        "/api/v1/webhooks/**",       // Chain event callbacks (signature-verified)
                        "/api/v1/merchants/register",// Merchant onboarding (public)
                        "/api/v2/merchants/register" // Merchant onboarding (public, v2)
                );

        // A2: payment orchestration now requires BOTH merchant API-key auth (above)
        // and a valid HMAC-SHA256 request signature (below).
        // P1-3 安全加固：HMAC 签名拦截器扩展到退款与订单确认端点，防止未签名请求
        // 篡改退款审批或订单状态。
        registry.addInterceptor(requestSignatureInterceptor)
                .addPathPatterns("/api/v1/payments/**", "/api/v1/refunds/**", "/api/v1/orders/**");

        // === P4-T6 多租户改造：租户 API Key 鉴权 + 租户级限流 ===
        // 顺序：先鉴权（填充 TenantContext），再限流（按租户配额判定）
        // 排除租户管理 API（admin 鉴权层保护，避免鸡生蛋问题）和公开端点
        registry.addInterceptor(tenantApiKeyInterceptor)
                .addPathPatterns("/api/v1/**", "/api/v2/**")
                .excludePathPatterns(
                        "/api/v1/checkout/**",
                        "/api/v1/webhooks/**",
                        "/api/v1/merchants/**",
                        "/api/v2/merchants/**",
                        "/api/v2/tenants/**"        // 租户管理 API（admin 鉴权）
                );

        registry.addInterceptor(tenantRateLimiter)
                .addPathPatterns("/api/v1/**", "/api/v2/**")
                .excludePathPatterns(
                        "/api/v1/checkout/**",
                        "/api/v1/webhooks/**",
                        "/api/v1/merchants/**",
                        "/api/v2/merchants/**",
                        "/api/v2/tenants/**"
                );
    }

    /**
     * Adapter that routes the Spring interceptor contract to the RateLimiter interface.
     */
    private static class RateLimitAdapter implements org.springframework.web.servlet.HandlerInterceptor {
        private final RateLimiter rateLimiter;

        RateLimitAdapter(RateLimiter rateLimiter) {
            this.rateLimiter = rateLimiter;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            String key = request.getHeader("X-NexusChain-ApiKey");
            if (key == null || key.isEmpty()) {
                key = request.getRemoteAddr();
            }
            if (!rateLimiter.tryAcquire(key)) {
                response.setStatus(429);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":42900,\"message\":\"Rate limit exceeded\",\"data\":null}");
                return false;
            }
            return true;
        }
    }
}