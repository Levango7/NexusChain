package org.nexus.gateway.orchestration.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.connector.PspTargetPolicy;
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
    private PspTargetPolicy pspTargetPolicy;
    private PaymentOrchestrationController controller;

    @BeforeEach
    void setUp() {
        orchestrationService = mock(OrchestrationService.class);
        registry = mock(ConnectorRegistry.class);
        // 真实 RoutingEngine 实例（repo=null，纯内存），与 Controller 共享同一 mock registry
        routingEngine = new RoutingEngine(registry, new GatewayConfig());
        // Top2 IDOR 加固：Controller 构造函数新增 MerchantOwnershipGuard 参数。
        // 本测试聚焦 routing-rules/connectors 运营配置面（不做商户归属校验），
        // guard 仅是被注入的依赖，不会被这些用例触达。
        // P0（2026-09-17）：注入显式策略。环境变量白名单仅含 PSP_X_API_KEY；
        // host 白名单含测试域名——命中白名单即跳过 DNS 解析，
        // 使单元测试不依赖网络，也不会因 .example 保留域不可解析而失败。
        pspTargetPolicy = new PspTargetPolicy("PSP_X_API_KEY", "api.pspx.example,d.example");
        controller = new PaymentOrchestrationController(orchestrationService, registry, routingEngine,
                new org.nexus.gateway.security.MerchantOwnershipGuard(), pspTargetPolicy);
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

    // === P0 安全加固（2026-09-17）：环境变量外泄 + SSRF ===

    @Test
    @DisplayName("POST connectors: api_key_env 不在白名单 -> 400（阻断任意环境变量外泄）")
    void apiKeyEnvOutsideAllowlistReturns400() {
        when(registry.get("psp-evil")).thenReturn(Optional.empty());

        Map<String, Object> body = Map.of(
                "id", "psp-evil",
                "type", "http_psp",
                "base_url", "https://api.pspx.example",
                // 未在白名单内的环境变量名（典型攻击目标）
                "api_key_env", "AWS_SECRET_ACCESS_KEY");

        ResponseEntity<Map<String, Object>> resp = controller.registerConnector(body);

        assertEquals(400, resp.getStatusCode().value());
        verify(registry, never()).register(any(PaymentConnector.class));
    }

    @Test
    @DisplayName("POST connectors: 白名单为空时 api_key_env 一律拒绝（默认关闭）")
    void apiKeyEnvRejectedWhenAllowlistEmpty() {
        PaymentOrchestrationController strict = new PaymentOrchestrationController(
                orchestrationService, registry, routingEngine,
                new org.nexus.gateway.security.MerchantOwnershipGuard(),
                new PspTargetPolicy("", ""));   // 两个白名单均为空

        when(registry.get("psp-y")).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> resp = strict.registerConnector(Map.of(
                "id", "psp-y", "type", "http_psp",
                "base_url", "https://api.pspx.example",
                "api_key_env", "PSP_X_API_KEY"));

        assertEquals(400, resp.getStatusCode().value());
        verify(registry, never()).register(any(PaymentConnector.class));
    }

    @Test
    @DisplayName("POST connectors: 云元数据地址 169.254.169.254 -> 400（SSRF 拦截）")
    void cloudMetadataAddressReturns400() {
        PaymentOrchestrationController noHostAllowlist = new PaymentOrchestrationController(
                orchestrationService, registry, routingEngine,
                new org.nexus.gateway.security.MerchantOwnershipGuard(),
                new PspTargetPolicy("", ""));   // 未配 host 白名单 → 走地址段校验

        when(registry.get("psp-meta")).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> resp = noHostAllowlist.registerConnector(Map.of(
                "id", "psp-meta", "type", "http_psp",
                "base_url", "http://169.254.169.254/latest/meta-data"));

        assertEquals(400, resp.getStatusCode().value());
        verify(registry, never()).register(any(PaymentConnector.class));
    }

    @Test
    @DisplayName("POST connectors: 回环地址与 file: 协议 -> 400")
    void loopbackAndFileSchemeReturn400() {
        PaymentOrchestrationController c = new PaymentOrchestrationController(
                orchestrationService, registry, routingEngine,
                new org.nexus.gateway.security.MerchantOwnershipGuard(),
                new PspTargetPolicy("", ""));

        when(registry.get("lp")).thenReturn(Optional.empty());
        when(registry.get("fl")).thenReturn(Optional.empty());

        assertEquals(400, c.registerConnector(Map.of(
                "id", "lp", "type", "http_psp", "base_url", "http://127.0.0.1:8080"))
                .getStatusCode().value());

        assertEquals(400, c.registerConnector(Map.of(
                "id", "fl", "type", "http_psp", "base_url", "file:///etc/passwd"))
                .getStatusCode().value());

        verify(registry, never()).register(any(PaymentConnector.class));
    }

    @Test
    @DisplayName("POST connectors: host 未命中白名单 -> 400")
    void hostOutsideAllowlistReturns400() {
        when(registry.get("psp-other")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.registerConnector(Map.of(
                "id", "psp-other", "type", "http_psp",
                "base_url", "https://attacker.example.com"));   // 不在白名单

        assertEquals(400, resp.getStatusCode().value());
        verify(registry, never()).register(any(PaymentConnector.class));
    }
}