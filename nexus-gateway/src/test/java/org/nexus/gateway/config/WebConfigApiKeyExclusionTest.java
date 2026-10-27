package org.nexus.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.interceptor.ApiKeyInterceptor;
import org.nexus.gateway.ratelimit.RateLimiter;
import org.nexus.gateway.security.RequestSignatureInterceptor;
import org.nexus.gateway.tenant.TenantApiKeyInterceptor;
import org.nexus.gateway.tenant.TenantRateLimiter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link WebConfig} 拦截器排除名单测试。
 *
 * <p>钉住 #50 收口的一处硬约束：审批决策路径必须从 {@code ApiKeyInterceptor}
 * 排除。运维只有 JWT、没有商户 API Key，漏掉这条排除会让端点恒 401
 * （风控审核端点此前就踩过同型坑，见 WebConfig 内注释）。</p>
 */
class WebConfigApiKeyExclusionTest {

    private static final String APPROVAL_DECISION = "/api/v1/signing/approvals/**";

    @Test
    @DisplayName("审批决策路径从 ApiKeyInterceptor 排除，且仍在 /api/v1/** 覆盖范围内")
    void approvalDecisionPathIsExcludedFromApiKeyInterceptor() {
        ApiKeyInterceptor apiKeyInterceptor = mock(ApiKeyInterceptor.class);
        ExposingRegistry registry = new ExposingRegistry();
        WebConfig config = new WebConfig(
                apiKeyInterceptor,
                mock(RateLimiter.class),
                mock(ApiVersionInterceptor.class),
                mock(RequestSignatureInterceptor.class),
                mock(TenantApiKeyInterceptor.class),
                mock(TenantRateLimiter.class));

        config.addInterceptors(registry);

        MappedInterceptor mapped = registry.interceptors().stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(m -> m.getInterceptor() == apiKeyInterceptor)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ApiKeyInterceptor 应已注册"));

        assertTrue(List.of(mapped.getIncludePathPatterns()).contains("/api/v1/**"),
                "ApiKeyInterceptor 仍覆盖 /api/v1/**");
        assertTrue(List.of(mapped.getExcludePathPatterns()).contains(APPROVAL_DECISION),
                "审批决策路径必须排除，否则运维无商户 API Key 会被 401");
    }

    @Test
    @DisplayName("/api/reconciliation/** 纳入限流（此前不受限）")
    void reconciliationLinkPathIsRateLimited() {
        ExposingRegistry registry = new ExposingRegistry();
        WebConfig config = new WebConfig(
                mock(ApiKeyInterceptor.class),
                mock(RateLimiter.class),
                mock(ApiVersionInterceptor.class),
                mock(RequestSignatureInterceptor.class),
                mock(TenantApiKeyInterceptor.class),
                mock(TenantRateLimiter.class));

        config.addInterceptors(registry);

        MappedInterceptor rateLimit = registry.interceptors().stream()
                .filter(MappedInterceptor.class::isInstance)
                .map(MappedInterceptor.class::cast)
                .filter(m -> "RateLimitAdapter".equals(m.getInterceptor().getClass().getSimpleName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("RateLimitAdapter 应已注册"));

        assertTrue(List.of(rateLimit.getIncludePathPatterns()).contains("/api/reconciliation/**"),
                "对账联动路径（非 v1）必须显式纳入限流");
    }

    /** {@code InterceptorRegistry#getInterceptors()} 是 protected，测试用子类暴露只读视图。 */
    private static class ExposingRegistry extends InterceptorRegistry {

        List<Object> interceptors() {
            return super.getInterceptors();
        }
    }
}
