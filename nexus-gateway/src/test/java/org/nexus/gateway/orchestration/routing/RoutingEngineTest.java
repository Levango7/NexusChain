package org.nexus.gateway.orchestration.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link RoutingEngine} 单元测试：覆盖 explicit / priority / weight / cost 策略、
 * dual-chain 规则、规则增删、fallback 等分支。
 */
class RoutingEngineTest {

    private ConnectorRegistry registry;
    private GatewayConfig cfg;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectorRegistry.class);
        cfg = new GatewayConfig();
    }

    private PaymentConnector mockConnector(String id, boolean active, int fee, String... currencies) {
        PaymentConnector c = mock(PaymentConnector.class);
        when(c.getId()).thenReturn(id);
        when(c.isActive()).thenReturn(active);
        when(c.feeBasisPoints()).thenReturn(fee);
        when(c.supportedCurrencies()).thenReturn(java.util.Set.of(currencies));
        return c;
    }

    // === explicit 路由 ===

    @Test
    @DisplayName("resolve: preferredConnector 指定且 active -> 仅返回该连接器")
    void resolve_explicitActive() {
        PaymentConnector chain = mockConnector("chain", true, 5, "NEX");
        when(registry.get("chain")).thenReturn(Optional.of(chain));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        List<PaymentConnector> result = engine.resolve("NEX", 1000, "chain");
        assertEquals(1, result.size());
        assertEquals("chain", result.get(0).getId());
    }

    @Test
    @DisplayName("resolve: preferredConnector 不存在 -> fallback")
    void resolve_explicitMissing_fallback() {
        when(registry.get("ghost")).thenReturn(Optional.empty());
        when(registry.getActiveForCurrency("NEX")).thenReturn(List.of());
        PaymentConnector any = mockConnector("mock", true, 0);
        when(registry.getActive()).thenReturn(List.of(any));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        List<PaymentConnector> result = engine.resolve("NEX", 1000, "ghost");
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("resolve: preferredConnector 非空但 inactive -> fallback")
    void resolve_explicitInactive_fallback() {
        PaymentConnector chain = mockConnector("chain", false, 5, "NEX");
        when(registry.get("chain")).thenReturn(Optional.of(chain));
        when(registry.getActiveForCurrency("NEX")).thenReturn(List.of());
        PaymentConnector any = mockConnector("mock", true, 0);
        when(registry.getActive()).thenReturn(List.of(any));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        List<PaymentConnector> result = engine.resolve("NEX", 1000, "chain");
        assertEquals(1, result.size());
    }

    // === priority 路由（默认规则）===

    @Test
    @DisplayName("resolve: NEX 走 default-nex 规则 -> chain, mock")
    void resolve_nexDefaultRule() {
        PaymentConnector chain = mockConnector("chain", true, 5, "NEX");
        PaymentConnector mock = mockConnector("mock", true, 0);
        when(registry.get("chain")).thenReturn(Optional.of(chain));
        when(registry.get("mock")).thenReturn(Optional.of(mock));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        List<PaymentConnector> result = engine.resolve("NEX", 1000, null);
        // default-nex 规则 connectors=[chain, mock]，按顺序返回
        assertTrue(result.size() >= 1);
        assertEquals("chain", result.get(0).getId());
    }

    @Test
    @DisplayName("resolve: 非 NEX 走 default-fallback 规则 -> mock, chain")
    void resolve_nonNexDefaultFallback() {
        PaymentConnector mock = mockConnector("mock", true, 0);
        PaymentConnector chain = mockConnector("chain", true, 5, "NEX");
        when(registry.get("mock")).thenReturn(Optional.of(mock));
        when(registry.get("chain")).thenReturn(Optional.of(chain));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        List<PaymentConnector> result = engine.resolve("USD", 1000, null);
        assertTrue(result.size() >= 1);
        assertEquals("mock", result.get(0).getId());
    }

    @Test
    @DisplayName("resolve: 规则连接器全部 inactive -> fallback")
    void resolve_priorityAllInactive_fallback() {
        // chain/mock 都 inactive，走 fallback
        when(registry.get("chain")).thenReturn(Optional.empty());
        when(registry.get("mock")).thenReturn(Optional.empty());
        PaymentConnector any = mockConnector("stripe", true, 10);
        when(registry.getActiveForCurrency("NEX")).thenReturn(List.of(any));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        List<PaymentConnector> result = engine.resolve("NEX", 1000, null);
        assertTrue(result.size() >= 1);
    }

    // === dual-chain 路由 ===

    @Test
    @DisplayName("resolve: dual-chain 启用，小金额走 small connectors")
    void resolve_dualChainSmall() {
        cfg.getRouting().getDualChain().setEnabled(true);
        cfg.getRouting().getDualChain().setSmallAmountThreshold(10000);
        cfg.getRouting().getDualChain().setSmall(List.of("consortium", "chain"));
        cfg.getRouting().getDualChain().setLarge(List.of("chain", "consortium"));

        PaymentConnector consortium = mockConnector("consortium", true, 2, "NEX");
        PaymentConnector chain = mockConnector("chain", true, 5, "NEX");
        when(registry.get("consortium")).thenReturn(Optional.of(consortium));
        when(registry.get("chain")).thenReturn(Optional.of(chain));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        // amount=500 < threshold=10000 -> small
        List<PaymentConnector> result = engine.resolve("NEX", 500, null);
        assertTrue(result.size() >= 1);
        assertEquals("consortium", result.get(0).getId());
    }

    @Test
    @DisplayName("resolve: dual-chain 启用，大金额走 large connectors")
    void resolve_dualChainLarge() {
        cfg.getRouting().getDualChain().setEnabled(true);
        cfg.getRouting().getDualChain().setSmallAmountThreshold(10000);
        cfg.getRouting().getDualChain().setSmall(List.of("consortium", "chain"));
        cfg.getRouting().getDualChain().setLarge(List.of("chain", "consortium"));

        PaymentConnector chain = mockConnector("chain", true, 5, "NEX");
        PaymentConnector consortium = mockConnector("consortium", true, 2, "NEX");
        when(registry.get("chain")).thenReturn(Optional.of(chain));
        when(registry.get("consortium")).thenReturn(Optional.of(consortium));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        // amount=50000 >= threshold=10000 -> large
        List<PaymentConnector> result = engine.resolve("NEX", 50000, null);
        assertTrue(result.size() >= 1);
        assertEquals("chain", result.get(0).getId());
    }

    // === 规则管理 ===

    @Test
    @DisplayName("addRule: 新增规则；getRules 包含；removeRule 移除")
    void addAndRemoveRule() {
        RoutingEngine engine = new RoutingEngine(registry, cfg);
        int initialSize = engine.getRules().size();

        RoutingRule rule = new RoutingRule("custom", "custom rule",
                java.util.Map.of("currency", "USD"), RoutingStrategy.PRIORITY,
                List.of("stripe"), 100);
        engine.addRule(rule);
        assertEquals(initialSize + 1, engine.getRules().size());

        engine.removeRule("custom");
        assertEquals(initialSize, engine.getRules().size());
    }

    @Test
    @DisplayName("addRule: 同 id 规则覆盖")
    void addRule_overwrite() {
        RoutingEngine engine = new RoutingEngine(registry, cfg);
        RoutingRule r1 = new RoutingRule("dup", "v1", java.util.Map.of(),
                RoutingStrategy.PRIORITY, List.of("a"), 10);
        RoutingRule r2 = new RoutingRule("dup", "v2", java.util.Map.of(),
                RoutingStrategy.PRIORITY, List.of("b"), 20);
        engine.addRule(r1);
        int sizeAfterFirst = engine.getRules().size();
        engine.addRule(r2);
        assertEquals(sizeAfterFirst, engine.getRules().size());
    }

    // === WEIGHT / COST 策略 ===

    @Test
    @DisplayName("resolve: WEIGHT 策略返回 shuffle 后的候选列表")
    void resolve_weightStrategy() {
        PaymentConnector chain = mockConnector("chain", true, 5, "NEX");
        PaymentConnector mock = mockConnector("mock", true, 0);
        when(registry.get("chain")).thenReturn(Optional.of(chain));
        when(registry.get("mock")).thenReturn(Optional.of(mock));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        RoutingRule rule = new RoutingRule("w", "weight", java.util.Map.of("currency", "NEX"),
                RoutingStrategy.WEIGHT, List.of("chain", "mock"), 200);
        engine.addRule(rule);

        List<PaymentConnector> result = engine.resolve("NEX", 1000, null);
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("resolve: COST 策略按 feeBasisPoints 升序排序")
    void resolve_costStrategy() {
        PaymentConnector chain = mockConnector("chain", true, 50, "NEX");
        PaymentConnector consortium = mockConnector("consFaux", true, 2, "NEX");
        PaymentConnector mock = mockConnector("mock", true, 10, "NEX");
        when(registry.get("chain")).thenReturn(Optional.of(chain));
        when(registry.get("consFaux")).thenReturn(Optional.of(consortium));
        when(registry.get("mock")).thenReturn(Optional.of(mock));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        RoutingRule rule = new RoutingRule("c", "cost", java.util.Map.of("currency", "NEX"),
                RoutingStrategy.COST, List.of("chain", "mock", "consFaux"), 200);
        engine.addRule(rule);

        List<PaymentConnector> result = engine.resolve("NEX", 1000, null);
        assertEquals(3, result.size());
        // 升序：consFaux(2) < mock(10) < chain(50)
        assertEquals("consFaux", result.get(0).getId());
        assertEquals("mock", result.get(1).getId());
        assertEquals("chain", result.get(2).getId());
    }

    @Test
    @DisplayName("resolve: COST 策略候选全 inactive -> fallback")
    void resolve_costAllInactive_fallback() {
        when(registry.get("chain")).thenReturn(Optional.empty());
        when(registry.get("mock")).thenReturn(Optional.empty());
        when(registry.getActiveForCurrency("NEX")).thenReturn(List.of());
        PaymentConnector any = mockConnector("stripe", true, 10);
        when(registry.getActive()).thenReturn(List.of(any));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        RoutingRule rule = new RoutingRule("c", "cost", java.util.Map.of("currency", "NEX"),
                RoutingStrategy.COST, List.of("chain", "mock"), 200);
        engine.addRule(rule);

        List<PaymentConnector> result = engine.resolve("NEX", 1000, null);
        assertTrue(result.size() >= 1);
    }

    @Test
    @DisplayName("resolve: EXPLICIT 策略走 priority 解析")
    void resolve_explicitStrategy() {
        PaymentConnector chain = mockConnector("chain", true, 5, "NEX");
        when(registry.get("chain")).thenReturn(Optional.of(chain));
        when(registry.get("mock")).thenReturn(Optional.empty());
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        RoutingRule rule = new RoutingRule("e", "explicit", java.util.Map.of("currency", "NEX"),
                RoutingStrategy.EXPLICIT, List.of("chain", "mock"), 200);
        engine.addRule(rule);

        List<PaymentConnector> result = engine.resolve("NEX", 1000, null);
        assertEquals(1, result.size());
        assertEquals("chain", result.get(0).getId());
    }

    // === fallback 路径 ===

    @Test
    @DisplayName("fallbackConnectors: getActiveForCurrency 非空时优先返回")
    void fallback_currencySpecific() {
        // 没有任何规则匹配（清空规则）+ 无 preferredConnector
        // 但默认规则会匹配，所以这里测试 fallback via explicit missing
        PaymentConnector stripe = mockConnector("stripe", true, 10, "USD");
        when(registry.get("ghost")).thenReturn(Optional.empty());
        when(registry.getActiveForCurrency("USD")).thenReturn(List.of(stripe));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        List<PaymentConnector> result = engine.resolve("USD", 1000, "ghost");
        assertEquals(1, result.size());
        assertEquals("stripe", result.get(0).getId());
    }

    @Test
    @DisplayName("fallbackConnectors: getActiveForCurrency 空时返回 getActive")
    void fallback_allActive() {
        PaymentConnector any = mockConnector("stripe", true, 10);
        when(registry.get("ghost")).thenReturn(Optional.empty());
        when(registry.getActiveForCurrency("USD")).thenReturn(List.of());
        when(registry.getActive()).thenReturn(List.of(any));
        RoutingEngine engine = new RoutingEngine(registry, cfg);

        List<PaymentConnector> result = engine.resolve("USD", 1000, "ghost");
        assertEquals(1, result.size());
    }
}