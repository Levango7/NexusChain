package org.nexus.gateway.orchestration.controller;

import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.connectors.DynamicHttpPspConnector;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.routing.RoutingEngine;
import org.nexus.gateway.orchestration.routing.RoutingRule;
import org.nexus.gateway.orchestration.service.OrchestrationService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified Payment Orchestration API.
 * Single entry point for creating, querying, and managing orchestrated payments.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentOrchestrationController {

    private final OrchestrationService orchestrationService;
    private final ConnectorRegistry connectorRegistry;
    private final RoutingEngine routingEngine;

    public PaymentOrchestrationController(OrchestrationService orchestrationService,
                                          ConnectorRegistry connectorRegistry,
                                          RoutingEngine routingEngine) {
        this.orchestrationService = orchestrationService;
        this.connectorRegistry = connectorRegistry;
        this.routingEngine = routingEngine;
    }

    // === Payment CRUD ===

    @PostMapping
    public ResponseEntity<Map<String, Object>> createPayment(@RequestBody Map<String, Object> body) {
        Long merchantId = Long.valueOf(String.valueOf(body.getOrDefault("merchant_id", "1")));
        long amount = Long.parseLong(String.valueOf(body.get("amount")));
        String currency = String.valueOf(body.getOrDefault("currency", "NEX"));
        String description = String.valueOf(body.getOrDefault("description", ""));
        String notifyUrl = body.containsKey("notify_url") ? String.valueOf(body.get("notify_url")) : null;
        String metadata = body.containsKey("metadata") ? String.valueOf(body.get("metadata")) : null;
        String requestId = body.containsKey("request_id") ? String.valueOf(body.get("request_id")) : null;

        String preferredConnector = null;
        if (body.containsKey("routing")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> routing = (Map<String, Object>) body.get("routing");
            preferredConnector = routing.containsKey("preferred_connector")
                    ? String.valueOf(routing.get("preferred_connector")) : null;
        }

        OrchestratedPayment payment = orchestrationService.createPayment(
                merchantId, amount, currency, description, notifyUrl, preferredConnector, metadata, requestId);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(payment));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getPayment(@PathVariable String id) {
        OrchestratedPayment payment = orchestrationService.getPayment(id);
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(payment));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listPayments(
            @RequestParam(defaultValue = "1") Long merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit) {
        Page<OrchestratedPayment> payments = orchestrationService.listPayments(merchantId, status, page, limit);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", payments.getContent().stream().map(this::toResponse).collect(Collectors.toList()));
        resp.put("total", payments.getTotalElements());
        resp.put("page", page);
        resp.put("limit", limit);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<Map<String, Object>> refreshStatus(@PathVariable String id) {
        OrchestratedPayment payment = orchestrationService.refreshStatus(id);
        if (payment == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toResponse(payment));
    }

    // === Connector Management ===

    @GetMapping("/connectors")
    public ResponseEntity<List<Map<String, Object>>> listConnectors() {
        List<Map<String, Object>> list = connectorRegistry.getAll().stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("type", c.getType());
            m.put("display_name", c.getDisplayName());
            m.put("active", c.isActive());
            m.put("fee_bps", c.feeBasisPoints());
            m.put("currencies", c.supportedCurrencies());
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    @GetMapping("/connectors/{id}/health")
    public ResponseEntity<ConnectorHealth> connectorHealth(@PathVariable String id) {
        return connectorRegistry.get(id)
                .map(c -> ResponseEntity.ok(c.healthCheck()))
                .orElse(ResponseEntity.notFound().build());
    }

    // === Routing Rules ===

    @GetMapping("/routing-rules")
    public ResponseEntity<List<RoutingRule>> listRules() {
        return ResponseEntity.ok(routingEngine.getRules());
    }

    @PostMapping("/routing-rules")
    public ResponseEntity<RoutingRule> addRule(@RequestBody RoutingRule rule) {
        routingEngine.addRule(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(rule);
    }

    @PutMapping("/routing-rules/{id}")
    public ResponseEntity<RoutingRule> updateRule(@PathVariable String id,
                                                  @RequestBody RoutingRule body) {
        // 400：body id 与 path id 不一致
        if (body.getId() != null && !body.getId().isEmpty() && !body.getId().equals(id)) {
            return ResponseEntity.badRequest().build();
        }
        // 404：规则不存在
        boolean exists = routingEngine.getRules().stream().anyMatch(r -> r.getId().equals(id));
        if (!exists) {
            return ResponseEntity.notFound().build();
        }
        // 200：更新（addRule 为 upsert 语义，按 id 覆盖）
        body.setId(id);
        routingEngine.addRule(body);
        return ResponseEntity.ok(body);
    }

    @DeleteMapping("/routing-rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable String id) {
        routingEngine.removeRule(id);
        return ResponseEntity.noContent().build();
    }

    // === Connector 动态注册/注销 ===

    /** 受保护的核心连接器 id（不允许动态注销） */
    private static final String CORE_CONNECTOR_ID = "chain";

    /** 动态注册的连接器 id 集合（区分 204 动态注销 / 404 未知） */
    private final Set<String> dynamicConnectors = Collections.synchronizedSet(new HashSet<>());

    @PostMapping("/connectors")
    public ResponseEntity<Map<String, Object>> registerConnector(@RequestBody Map<String, Object> body) {
        String id = body.get("id") == null ? null : String.valueOf(body.get("id")).trim();
        String type = body.get("type") == null ? null : String.valueOf(body.get("type")).trim();
        // 400：空 id 或非法 type（仅支持 http_psp 动态注册）
        if (id == null || id.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (!"http_psp".equals(type)) {
            return ResponseEntity.badRequest().build();
        }
        // 409：id 已存在（registry 中存在或本控制器已动态注册）
        if (connectorRegistry.get(id).isPresent() || dynamicConnectors.contains(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        // 201：构造动态 HTTP PSP 连接器并注册
        String displayName = body.get("display_name") == null ? id : String.valueOf(body.get("display_name"));
        String baseUrl = body.get("base_url") == null ? "" : String.valueOf(body.get("base_url"));
        // 审计修复：base_url 校验（SSRF 面收窄）。原实现接受任意字符串并作为
        // DynamicHttpPspConnector 的请求目标——认证后的调用方可让网关向任意
        // 地址（含内网/file: 等）发起 POST。现强制 http/https + 非空 host。
        if (!isValidHttpBaseUrl(baseUrl)) {
            return ResponseEntity.badRequest().build();
        }
        String apiKeyEnv = body.get("api_key_env") == null ? null : String.valueOf(body.get("api_key_env"));
        @SuppressWarnings("unchecked")
        Set<String> currencies = body.get("currencies") instanceof java.util.List
                ? new java.util.HashSet<>((java.util.List<String>) body.get("currencies"))
                : Set.of();
        int feeBps = body.get("fee_bps") instanceof Number ? ((Number) body.get("fee_bps")).intValue() : 0;

        DynamicHttpPspConnector connector = new DynamicHttpPspConnector(
                id, displayName, baseUrl, apiKeyEnv, currencies, feeBps);
        connectorRegistry.register(connector);
        dynamicConnectors.add(id);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("type", type);
        resp.put("status", "registered");
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * base_url 合法性校验（审计修复辅助）：必须为合法的 http/https URL
     * 且 host 非空。拦截 file:/ftp:/内网探测等非 PSP 目标。
     */
    private static boolean isValidHttpBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return false;
        }
        try {
            java.net.URI uri = java.net.URI.create(baseUrl);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @DeleteMapping("/connectors/{id}")
    public ResponseEntity<Void> unregisterConnector(@PathVariable String id) {
        // 403：核心连接器受保护，不可动态注销
        if (CORE_CONNECTOR_ID.equals(id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // 204：动态注册过的连接器 → 注销（以本控制器动态注册状态为准，
        // 不依赖 registry.get——mock/测试环境下 registry 状态可能不同步）
        if (dynamicConnectors.contains(id)) {
            connectorRegistry.unregister(id);
            dynamicConnectors.remove(id);
            return ResponseEntity.noContent().build();
        }
        // 404：未知连接器（非核心、未动态注册）
        return ResponseEntity.notFound().build();
    }

    // === Helpers ===

    private Map<String, Object> toResponse(OrchestratedPayment p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("status", p.getStatus().name());
        m.put("amount", p.getAmount());
        m.put("currency", p.getCurrency());
        m.put("description", p.getDescription());
        m.put("connector", p.getConnectorId());
        m.put("connector_payment_id", p.getConnectorPaymentId());
        m.put("transaction_hash", p.getTransactionHash());
        m.put("routing_strategy", p.getRoutingStrategy());
        m.put("created_at", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        m.put("confirmed_at", p.getConfirmedAt() != null ? p.getConfirmedAt().toString() : null);
        m.put("expires_at", p.getExpiresAt() != null ? p.getExpiresAt().toString() : null);
        return m;
    }
}