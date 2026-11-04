package org.nexus.apigateway.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Protects only the Prometheus actuator endpoint of the WebFlux-based API gateway.
 *
 * <p>This module must not use the servlet stack, so it uses a {@link WebFilter} rather than a
 * servlet {@code Filter}. The health endpoint remains intentionally unauthenticated because
 * Kubernetes probes depend on it.</p>
 *
 * <p>Absence of a configured token fails closed. During credential rotation the filter accepts
 * the current token and an optional previous token so monitoring and deployments can be rolled
 * independently without an authentication gap (PLAN-077).</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActuatorPrometheusAuthFilter implements WebFilter {

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
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!METRICS_PATH.equals(path)) {
            return chain.filter(exchange);
        }

        if (!isTokenConfigured()) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, "metrics authentication not configured");
        }

        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!isExpectedBearer(authorization)) {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                    "Bearer realm=\"actuator/prometheus\"");
            return reject(exchange, HttpStatus.UNAUTHORIZED, "missing or invalid metrics token");
        }

        return chain.filter(exchange);
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

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        byte[] body = ("{\"code\":" + status.value() + ",\"message\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
