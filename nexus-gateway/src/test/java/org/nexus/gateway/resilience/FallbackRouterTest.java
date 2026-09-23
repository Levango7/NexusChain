package org.nexus.gateway.resilience;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link FallbackRouter} 单元测试 — 测试降级路由逻辑。
 */
class FallbackRouterTest {

    private ConnectorCircuitBreaker circuitBreaker;
    private ApplicationEventPublisher eventPublisher;
    private FallbackRouter fallbackRouter;

    @BeforeEach
    void setUp() {
        circuitBreaker = mock(ConnectorCircuitBreaker.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        fallbackRouter = new FallbackRouter(circuitBreaker, eventPublisher);
    }

    @Test
    @DisplayName("routeToFallback — 主 connector 熔断时路由到第一个可用备选")
    void routeToFallback_firstAvailable() {
        fallbackRouter.configureRoutes(Map.of(
                "chain", List.of("consortium", "mock")
        ));

        // consortium 未熔断
        when(circuitBreaker.isCircuitOpen("consortium")).thenReturn(false);

        String result = fallbackRouter.routeToFallback("chain");

        assertEquals("consortium", result, "应路由到第一个未熔断的备选 connector");
    }

    @Test
    @DisplayName("routeToFallback — 第一个备选也熔断时路由到第二个")
    void routeToFallback_skipOpenFallback() {
        fallbackRouter.configureRoutes(Map.of(
                "chain", List.of("consortium", "mock")
        ));

        when(circuitBreaker.isCircuitOpen("consortium")).thenReturn(true);
        when(circuitBreaker.isCircuitOpen("mock")).thenReturn(false);

        String result = fallbackRouter.routeToFallback("chain");

        assertEquals("mock", result, "应跳过熔断的备选，路由到第二个可用备选");
    }

    @Test
    @DisplayName("routeToFallback — 所有备选都熔断时返回 null")
    void routeToFallback_allOpen_returnsNull() {
        fallbackRouter.configureRoutes(Map.of(
                "chain", List.of("consortium", "mock")
        ));

        when(circuitBreaker.isCircuitOpen("consortium")).thenReturn(true);
        when(circuitBreaker.isCircuitOpen("mock")).thenReturn(true);

        String result = fallbackRouter.routeToFallback("chain");

        assertNull(result, "所有备选都熔断时应返回 null");
    }

    @Test
    @DisplayName("routeToFallback — 无降级路由配置时返回 null")
    void routeToFallback_noConfig_returnsNull() {
        String result = fallbackRouter.routeToFallback("unknown_connector");
        assertNull(result, "无降级路由配置时应返回 null");
    }

    @Test
    @DisplayName("routeToFallback — 降级时发布 FallbackEvent")
    void routeToFallback_publishesEvent() {
        fallbackRouter.configureRoutes(Map.of(
                "chain", List.of("consortium")
        ));

        when(circuitBreaker.isCircuitOpen("consortium")).thenReturn(false);

        fallbackRouter.routeToFallback("chain");

        verify(eventPublisher, times(1)).publishEvent(any(FallbackEvent.class));
    }

    @Test
    @DisplayName("hasFallback — 有配置返回 true，无配置返回 false")
    void hasFallback_correctResult() {
        fallbackRouter.configureRoutes(Map.of(
                "chain", List.of("consortium", "mock")
        ));

        assertTrue(fallbackRouter.hasFallback("chain"), "有降级配置的 connector 应返回 true");
        assertFalse(fallbackRouter.hasFallback("stripe"), "无降级配置的 connector 应返回 false");
    }

    @Test
    @DisplayName("updateRoute — 更新单个 connector 的降级路由")
    void updateRoute_updatesConfig() {
        fallbackRouter.configureRoutes(Map.of(
                "chain", List.of("consortium")
        ));

        fallbackRouter.updateRoute("chain", List.of("consortium", "mock"));

        assertEquals(List.of("consortium", "mock"), fallbackRouter.getFallbacks("chain"),
                "更新后应返回新的降级路由列表");
    }

    @Test
    @DisplayName("getFallbacks — 返回不可变列表")
    void getFallbacks_returnsImmutableList() {
        fallbackRouter.configureRoutes(Map.of(
                "chain", List.of("consortium", "mock")
        ));

        List<String> fallbacks = fallbackRouter.getFallbacks("chain");

        assertThrows(UnsupportedOperationException.class, () -> fallbacks.add("new_connector"),
                "返回的列表应为不可变");
    }

    @Test
    @DisplayName("getAllRoutes — 返回所有降级路由配置")
    void getAllRoutes_returnsAll() {
        fallbackRouter.configureRoutes(Map.of(
                "chain", List.of("consortium", "mock"),
                "stripe", List.of("adyen", "mock")
        ));

        Map<String, List<String>> routes = fallbackRouter.getAllRoutes();

        assertEquals(2, routes.size(), "应返回 2 个 connector 的降级路由配置");
        assertTrue(routes.containsKey("chain"));
        assertTrue(routes.containsKey("stripe"));
    }

    @Test
    @DisplayName("getFallbacks — 无配置的 connector 返回空列表")
    void getFallbacks_noConfig_returnsEmpty() {
        List<String> fallbacks = fallbackRouter.getFallbacks("unknown");

        assertNotNull(fallbacks, "无配置时应返回非 null 的空列表");
        assertTrue(fallbacks.isEmpty(), "无配置时应返回空列表");
    }
}