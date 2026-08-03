package org.nexus.gateway.config;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * API version negotiation interceptor.
 * Supports versioning via:
 *   1. URL path: /api/v1/..., /api/v2/...
 *   2. Header: Accept-Version: v1 | v2
 * Adds deprecation headers for old versions.
 */
@Component
public class ApiVersionInterceptor implements HandlerInterceptor {

    public static final String CURRENT_VERSION = "v1";
    public static final String VERSION_HEADER = "X-NexusChain-Api-Version";
    public static final String DEPRECATION_HEADER = "X-NexusChain-Deprecated";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();
        String version = extractVersion(path, request);

        // Set response version header
        response.setHeader(VERSION_HEADER, version);

        // Mark deprecated versions
        if (!CURRENT_VERSION.equals(version)) {
            response.setHeader(DEPRECATION_HEADER, "true");
            response.setHeader("X-NexusChain-Sunset", "2027-01-01");
        }

        // Store resolved version for downstream use
        request.setAttribute("nexus.apiVersion", version);
        return true;
    }

    private String extractVersion(String path, HttpServletRequest request) {
        // 1. Try URL path: /api/v1/... or /api/v2/...
        if (path.matches("/api/v\\\\d+/.*")) {
            String[] parts = path.split("/");
            if (parts.length >= 3) return parts[2]; // "v1", "v2", etc.
        }
        // 2. Try Accept-Version header
        String headerVersion = request.getHeader("Accept-Version");
        if (headerVersion != null && headerVersion.matches("v\\\\d+")) {
            return headerVersion;
        }
        // 3. Default to current version
        return CURRENT_VERSION;
    }
}