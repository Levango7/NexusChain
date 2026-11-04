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
 * <p>Absence of a configured token fails closed. The shared {@code NEXUS_METRICS_TOKEN} is
 * supplied by the deployment secret and never committed to the repository.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActuatorPrometheusAuthFilter implements WebFilter {

    private static final String METRICS_PATH = "/actuator/prometheus";
    private static final String BEARER_PREFIX = "Bearer ";

    private final String expectedToken;

    public ActuatorPrometheusAuthFilter(@Value("${nexus.actuator.prometheus-token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
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
        return MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
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
