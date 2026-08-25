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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Unified Payment Orchestration API.
 * Single entry point for creating, querying, and managing orchestrated payments.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentOrchestrationController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(PaymentOrchestrationController.class);

    private final OrchestrationService orchestrationService;
    private final ConnectorRegistry connectorRegistry;
    private final RoutingEngine routingEngine;
    /** 本实例运行期动态注册的连接器 id；核心（静态装配）连接器不在其中，删除受保护。 */
    private final Set<String> dynamicConnectorIds = ConcurrentHashMap.newKeySet();

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
        // P0 安全修复：解析付款人/收款人链上地址并透传到编排服务，避免空地址转账。
        String payeeAddress = body.containsKey("payee_address") ? String.valueOf(body.get("payee_address")) : null;
        String payerAddress = body.containsKey("payer_address") ? String.valueOf(body.get("payer_address")) : null;

        String preferredConnector = null;
        if (body.containsKey("routing")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> routing = (Map<String, Object>) body.get("routing");
            preferredConnector = routing.containsKey("preferred_connector")
                    ? String.valueOf(routing.get("preferred_connector")) : null;
        }

        OrchestratedPayment payment = orchestrationService.createPayment(
                merchantId, amount, currency, description, notifyUrl,
                preferredConnector, metadata, requestId, payeeAddress, payerAddress);

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

    /**
     * Dynamically register an HTTP PSP connector at runtime.
     * Admin-only. Only {@code type=http_psp} is supported.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/connectors")
    public ResponseEntity<Map<String, Object>> registerConnector(@RequestBody Map<String, Object> body) {
        String id = strOrNull(body.get("id"));
        String type = strOrNull(body.get("type"));
        if (id == null || id.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (!"http_psp".equals(type)) {
            return ResponseEntity.badRequest().build();
        }
        if (connectorRegistry.get(id).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        String displayName = body.containsKey("display_name") ? strOrNull(body.get("display_name")) : id;
        String baseUrl = strOrNull(body.get("base_url"));
        String apiKeyEnv = strOrNull(body.get("api_key_env"));
        Set<String> currencies = parseCurrencies(body.get("currencies"));
        int feeBps = body.containsKey("fee_bps") ? parseIntOrDefault(body.get("fee_bps"), 0) : 0;

        DynamicHttpPspConnector connector =
                new DynamicHttpPspConnector(id, displayName, baseUrl, apiKeyEnv, currencies, feeBps);
        connectorRegistry.register(connector);
        dynamicConnectorIds.add(id);
        logRegistered(id);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("type", "http_psp");
        resp.put("display_name", displayName);
        resp.put("base_url", baseUrl);
        resp.put("api_key_env", apiKeyEnv);
        resp.put("currencies", currencies);
        resp.put("fee_bps", feeBps);
        resp.put("status", "registered");
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * Unregister a dynamically registered connector.
     * Core (statically wired) connectors are protected and cannot be deleted (403).
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/connectors/{id}")
    public ResponseEntity<Void> unregisterConnector(@PathVariable String id) {
        if (!dynamicConnectorIds.contains(id)) {
            // 非动态连接器：存在则为核心连接器，禁止删除；不存在则 404
            if (connectorRegistry.get(id).isPresent()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            return ResponseEntity.notFound().build();
        }
        connectorRegistry.unregister(id);
        dynamicConnectorIds.remove(id);
        logUnregistered(id);
        return ResponseEntity.noContent().build();
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

    @DeleteMapping("/routing-rules/{id}")
    public ResponseEntity<Void> deleteRule(@PathVariable String id) {
        routingEngine.removeRule(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/routing-rules/{id}")
    public ResponseEntity<RoutingRule> updateRule(@PathVariable String id, @RequestBody RoutingRule rule) {
        if (rule.getId() == null || rule.getId().isBlank()) {
            rule.setId(id);
        } else if (!rule.getId().equals(id)) {
            return ResponseEntity.badRequest().build();
        }
        boolean exists = routingEngine.getRules().stream().anyMatch(r -> r.getId().equals(id));
        if (!exists) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(routingEngine.updateRule(id, rule));
    }

    // === Helpers ===

    private static String strOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> parseCurrencies(Object value) {
        if (!(value instanceof Collection<?> collection)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object item : collection) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static int parseIntOrDefault(Object value, int defaultValue) {
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException | NullPointerException e) {
            return defaultValue;
        }
    }

    private void logRegistered(String id) {
        log.info("Dynamic connector registered via API: {} (total dynamic={})", id, dynamicConnectorIds.size());
    }

    private void logUnregistered(String id) {
        log.info("Dynamic connector unregistered via API: {}", id);
    }

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