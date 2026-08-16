package org.nexus.apigateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RateLimitFilter 测试（安全关键层补盲区——Redis 令牌桶限流 + 降级）。
 */
class RateLimitFilterTest {

    private ReactiveStringRedisTemplate redisTemplate;
    private RateLimitFilter filter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        filter = new RateLimitFilter(redisTemplate);
        setField(filter, "enabled", true);
        setField(filter, "capacity", 100);
        setField(filter, "refillRate", 10L);
        setField(filter, "failClosed", false);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private MockServerWebExchange exchange() {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/payments")
                        .header(AuthenticationFilter.HEADER_API_KEY, "test-key").build());
    }

    @SuppressWarnings("unchecked")
    private void mockRedisResult(Object value) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn((Flux<Object>) Flux.just(value));
    }

    @Test
    void disabled_passesThrough() throws Exception {
        setField(filter, "enabled", false);
        GatewayFilterChain c = mock(GatewayFilterChain.class);
        when(c.filter(any())).thenReturn(Mono.empty());
        filter.filter(exchange(), c).block();
        verify(c, times(1)).filter(any());
    }

    @Test
    void redisAllows_passes() {
        mockRedisResult(1L);  // 允许
        GatewayFilterChain c = mock(GatewayFilterChain.class);
        when(c.filter(any())).thenReturn(Mono.empty());
        var ex = exchange();
        filter.filter(ex, c).block();
        verify(c, times(1)).filter(any());
        assertEquals("100", ex.getResponse().getHeaders().getFirst("X-RateLimit-Limit"),
                "限流头应写入容量");
    }

    @Test
    void redisRejects_returnsRateLimited() {
        mockRedisResult(0L);  // 拒绝
        GatewayFilterChain c = mock(GatewayFilterChain.class);
        when(c.filter(any())).thenReturn(Mono.empty());
        var ex = exchange();
        filter.filter(ex, c).block();
        verify(c, never()).filter(any());
        assertEquals(429, ex.getResponse().getStatusCode().value(), "限流触发应返回 429");
    }

    @Test
    void redisError_failOpen_passes() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("redis down")));
        GatewayFilterChain c = mock(GatewayFilterChain.class);
        when(c.filter(any())).thenReturn(Mono.empty());
        filter.filter(exchange(), c).block();
        verify(c, times(1)).filter(any());
    }

    @Test
    void redisError_failClosed_rejects() throws Exception {
        setField(filter, "failClosed", true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyList()))
                .thenReturn(Flux.error(new RuntimeException("redis down")));
        GatewayFilterChain c = mock(GatewayFilterChain.class);
        when(c.filter(any())).thenReturn(Mono.empty());
        filter.filter(exchange(), c).block();
        verify(c, never()).filter(any());
    }
}
