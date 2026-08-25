package org.nexus.gateway.orchestration.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.routing.RoutingEngine;
import org.nexus.gateway.orchestration.routing.RoutingRule;
import org.nexus.gateway.orchestration.routing.RoutingStrategy;
import org.nexus.gateway.orchestration.service.OrchestrationService;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link PaymentOrchestrationController} 新增端点单元测试（直接实例化，无 Spring 上下文）：
 * <ul>
 *   <li>PUT /routing-rules/&#123;id&#125;：200 更新 / 404 不存在 / 400 path-body id 不一致</li>
 *   <li>POST /connectors：201 注册 / 409 重复 / 400 非法 type 或空 id</li>
 *   <li>DELETE /connectors/&#123;id&#125;：204 动态注销 / 403 核心保护 / 404 未知</li>
 * </ul>
 */
class PaymentOrchestrationControllerConnectorApiTest {

    private OrchestrationService orchestrationService;
    private ConnectorRegistry registry;
    private RoutingEngine routingEngine;
    private PaymentOrchestrationController controller;

    @BeforeEach
    void setUp() {
        orchestrationService = mock(OrchestrationService.class);
        registry = mock(ConnectorRegistry.class);
        // 真实 RoutingEngine 实例（repo=null，纯内存），与 Controller 共享同一 mock registry
        routingEngine = new RoutingEngine(registry, new GatewayConfig());
        controller = new PaymentOrchestrationController(orchestrationService, registry, routingEngine);
    }

    // === PUT /routing-rules/{id} ===

    @Test
    @DisplayName("PUT routing-rules: 更新已有规则 -> 200 且内存生效")
    void putExistingRuleReturns200() {
        routingEngine.addRule(new RoutingRule("r1", "old name",
                Map.of(), RoutingStrategy.PRIORITY, List.of("mock"), 1));

        RoutingRule body = new RoutingRule();
        body.setName("new name");
        body.setConditions(Map.of("currency", "EUR"));
        body.setStrategy(RoutingStrategy.COST);
        body.setConnectors(List.of("stripe"));
        body.setPriority(99);

        ResponseEntity<RoutingRule> resp = controller.updateRule("r1", body);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("r1", resp.getBody().getId());
        RoutingRule inMemory = routingEngine.getRules().stream()
                .filter(r -> r.getId().equals("r1")).findFirst().orElseThrow();
        assertEquals(99, inMemory.getPriority());
        assertEquals(List.of("stripe"), inMemory.getConnectors());
    }

    @Test
    @DisplayName("PUT routing-rules: 规则不存在 -> 404")
    void putMissingRuleReturns404() {
        RoutingRule body = new RoutingRule();
        body.setStrategy(RoutingStrategy.PRIORITY);

        ResponseEntity<RoutingRule> resp = controller.updateRule("ghost", body);

        assertEquals(404, resp.getStatusCode().value());
        assertTrue(routingEngine.getRules().stream().noneMatch(r -> r.getId().equals("ghost")));
    }

    @Test
    @DisplayName("PUT routing-rules: body id 与 path id 不一致 -> 400")
    void putIdMismatchReturns400() {
        RoutingRule body = new RoutingRule();
        body.setId("other");

        ResponseEntity<RoutingRule> resp = controller.updateRule("r1", body);

        assertEquals(400, resp.getStatusCode().value());
    }

    // === POST /connectors ===

    @Test
    @DisplayName("POST connectors: 合法 http_psp -> 201 并注册到 registry")
    void postConnectorRegistersAndReturns201() {
        when(registry.get("psp-x")).thenReturn(Optional.empty());

        Map<String, Object> body = Map.of(
                "id", "psp-x",
                "type", "http_psp",
                "display_name", "PSP X",
                "base_url", "https://api.pspx.example",
                "api_key_env", "PSP_X_API_KEY",
                "currencies", List.of("USD", "EUR"),
                "fee_bps", 150);

        ResponseEntity<Map<String, Object>> resp = controller.registerConnector(body);

        assertEquals(201, resp.getStatusCode().value());
        assertEquals("psp-x", resp.getBody().get("id"));
        assertEquals("http_psp", resp.getBody().get("type"));
        assertEquals("registered", resp.getBody().get("status"));
        verify(registry).register(any(PaymentConnector.class));
    }

    @Test
    @DisplayName("POST connectors: id 已存在 -> 409 且不重复注册")
    void postDuplicateConnectorReturns409() {
        PaymentConnector existing = mock(PaymentConnector.class);
        when(existing.getId()).thenReturn("chain");
        when(registry.get("chain")).thenReturn(Optional.of(existing));

        Map<String, Object> body = Map.of("id", "chain", "type", "http_psp");

        ResponseEntity<Map<String, Object>> resp = controller.registerConnector(body);

        assertEquals(409, resp.getStatusCode().value());
        verify(registry, never()).register(any(PaymentConnector.class));
    }

    @Test
    @DisplayName("POST connectors: 非法 type / 空 id -> 400")
    void postInvalidTypeReturns400() {
        ResponseEntity<Map<String, Object>> badType =
                controller.registerConnector(Map.of("id", "s1", "type", "stripe"));
        assertEquals(400, badType.getStatusCode().value());

        ResponseEntity<Map<String, Object>> emptyId =
                controller.registerConnector(Map.of("type", "http_psp"));
        assertEquals(400, emptyId.getStatusCode().value());

        ResponseEntity<Map<String, Object>> blankId =
                controller.registerConnector(Map.of("id", "", "type", "http_psp"));
        assertEquals(400, blankId.getStatusCode().value());

        verify(registry, never()).register(any(PaymentConnector.class));
    }

    // === DELETE /connectors/{id} ===

    @Test
    @DisplayName("DELETE connectors: 动态注册的连接器 -> 204 并从 registry 注销")
    void deleteDynamicConnectorReturns204() {
        when(registry.get("dyn-psp")).thenReturn(Optional.empty());
        controller.registerConnector(Map.of(
                "id", "dyn-psp", "type", "http_psp", "base_url", "https://d.example"));

        ResponseEntity<Void> resp = controller.unregisterConnector("dyn-psp");

        assertEquals(204, resp.getStatusCode().value());
        verify(registry).unregister("dyn-psp");
    }

    @Test
    @DisplayName("DELETE connectors: 核心连接器(chain) -> 403 且不被注销")
    void deleteCoreConnectorReturns403() {
        PaymentConnector core = mock(PaymentConnector.class);
        when(core.getId()).thenReturn("chain");
        when(registry.get("chain")).thenReturn(Optional.of(core));

        ResponseEntity<Void> resp = controller.unregisterConnector("chain");

        assertEquals(403, resp.getStatusCode().value());
        verify(registry, never()).unregister(any(String.class));
    }

    @Test
    @DisplayName("DELETE connectors: 未知连接器 -> 404")
    void deleteUnknownConnectorReturns404() {
        when(registry.get("ghost")).thenReturn(Optional.empty());

        ResponseEntity<Void> resp = controller.unregisterConnector("ghost");

        assertEquals(404, resp.getStatusCode().value());
        verify(registry, never()).unregister(any(String.class));
    }
}