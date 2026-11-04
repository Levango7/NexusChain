package org.nexus.apigateway.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActuatorPrometheusAuthFilterTest {

    private static WebFilterChain chain() {
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
        return chain;
    }

    @Test
    void unconfiguredTokenFailsClosed() {
        ActuatorPrometheusAuthFilter filter = new ActuatorPrometheusAuthFilter("");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/prometheus").build());

        filter.filter(exchange, chain()).block();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exchange.getResponse().getStatusCode());
    }

    @Test
    void rejectsWrongToken() {
        ActuatorPrometheusAuthFilter filter = new ActuatorPrometheusAuthFilter("expected-token");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer wrong").build());
        WebFilterChain chain = chain();

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(chain, never()).filter(any());
    }

    @Test
    void acceptsExpectedToken() {
        ActuatorPrometheusAuthFilter filter = new ActuatorPrometheusAuthFilter("expected-token");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/prometheus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expected-token").build());
        WebFilterChain chain = chain();

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }

    @Test
    void leavesHealthUnauthenticated() {
        ActuatorPrometheusAuthFilter filter = new ActuatorPrometheusAuthFilter("expected-token");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());
        WebFilterChain chain = chain();

        filter.filter(exchange, chain).block();

        verify(chain).filter(exchange);
    }
}
