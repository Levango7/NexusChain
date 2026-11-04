package org.nexus.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

/**
 * Protects only the Prometheus actuator endpoint.
 *
 * <p>Spring MVC interceptors cannot protect Boot's independent actuator handler mapping. A
 * servlet filter is required for {@code /actuator/prometheus}. The health endpoint remains
 * intentionally unauthenticated because Kubernetes probes and local compose health checks are
 * operational liveness/readiness dependencies.</p>
 *
 * <p>Absence of a configured token fails closed. During credential rotation the filter accepts
 * the current token and an optional previous token so Prometheus and the applications can be
 * rolled independently without an authentication gap (PLAN-077).</p>
 */
@Component
@Order(0)
public class ActuatorPrometheusAuthFilter implements Filter {

    private static final String METRICS_PATH = "/actuator/prometheus";
    private static final String BEARER_PREFIX = "Bearer ";

    private final String expectedToken;
    private final String previousToken;

    public ActuatorPrometheusAuthFilter(
            @Value("${nexus.actuator.prometheus-token:}") String expectedToken,
            @Value("${nexus.actuator.prometheus-previous-token:}") String previousToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
        this.previousToken = previousToken == null ? "" : previousToken;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        String path = httpRequest.getRequestURI();
        if (!METRICS_PATH.equals(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (!isTokenConfigured()) {
            reject(httpResponse, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "metrics authentication not configured");
            return;
        }

        String authorization = httpRequest.getHeader("Authorization");
        if (!isExpectedBearer(authorization)) {
            httpResponse.setHeader("WWW-Authenticate", "Bearer realm=\"actuator/prometheus\"");
            reject(httpResponse, HttpServletResponse.SC_UNAUTHORIZED, "missing or invalid metrics token");
            return;
        }

        chain.doFilter(request, response);
    }

    /** fail-closed 只由 current token 决定：previous 单独存在不算已配置。 */
    private boolean isTokenConfigured() {
        return expectedToken != null && !expectedToken.isBlank();
    }

    private boolean isExpectedBearer(String authorization) {
        if (authorization == null || authorization.length() < BEARER_PREFIX.length() + 1) {
            return false;
        }
        if (!authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        String candidate = authorization.substring(BEARER_PREFIX.length());
        return matches(expectedToken, candidate) || matches(previousToken, candidate);
    }

    private static boolean matches(String expected, String candidate) {
        return expected != null && !expected.isBlank()
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        candidate.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
