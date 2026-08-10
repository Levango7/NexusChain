package org.nexus.gateway.config;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API version negotiation interceptor (legacy, retained for backward compatibility).
 *
 * <p>Phase 4 T7: 版本解析与弃用头注入已迁移至 {@link org.nexus.gateway.apiversion.ApiVersionFilter}
 * （Servlet Filter，在拦截器链之前执行）。本拦截器保留为兼容层，仅做请求属性设置，
 * 不再重复设置响应头（避免覆盖 Filter 的值）。</p>
 *
 * <p>历史 bug 修复：URL 路径正则 {@code /api/v\d+/} 之前因双反斜杠转义错误而永不匹配，
 * 现已修正。</p>
 */
@Component
public class ApiVersionInterceptor implements HandlerInterceptor {

    public static final String CURRENT_VERSION = "v1";
    public static final String VERSION_HEADER = "X-NexusChain-Api-Version";
    public static final String DEPRECATION_HEADER = "X-NexusChain-Deprecated";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // ApiVersionFilter 已在 Filter 链完成版本解析、响应头注入与弃用声明。
        // 此处仅做兼容性请求属性设置（若 Filter 已设置则不覆盖）。
        if (request.getAttribute("nexus.apiVersionLabel") == null) {
            String path = request.getRequestURI();
            String version = extractVersion(path, request);
            request.setAttribute("nexus.apiVersion", version.startsWith("v") ? version.substring(1) : version);
            request.setAttribute("nexus.apiVersionLabel", version);
        }
        return true;
    }

    private String extractVersion(String path, HttpServletRequest request) {
        // 1. Try URL path: /api/v1/... or /api/v2/...
        if (path != null && path.matches("/api/v\\d+/.*")) {
            String[] parts = path.split("/");
            if (parts.length >= 3) return parts[2]; // "v1", "v2", etc.
        }
        // 2. Try Accept-Version header
        String headerVersion = request.getHeader("Accept-Version");
        if (headerVersion != null && headerVersion.matches("v\\d+")) {
            return headerVersion;
        }
        // 3. Default to current version
        return CURRENT_VERSION;
    }
}