package org.nexus.gateway.web;

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
 * <p>Absence of a configured token fails closed. Production and local metrics wiring must
 * provide the same secret through a secret manager, never with a literal token in source.</p>
 */
@Component
@Order(0)
public class ActuatorPrometheusAuthFilter implements Filter {

    private static final String METRICS_PATH = "/actuator/prometheus";
    private final String expectedToken;

    public ActuatorPrometheusAuthFilter(@Value("${nexus.actuator.prometheus-token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
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

    private boolean isTokenConfigured() {
        return expectedToken != null && !expectedToken.isBlank();
    }

    private boolean isExpectedBearer(String authorization) {
        if (authorization == null || authorization.length() < "Bearer ".length() + 1) {
            return false;
        }
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return false;
        }
        String candidate = authorization.substring("Bearer ".length());
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
