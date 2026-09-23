package org.nexus.gateway.orchestration.controller;

import org.nexus.gateway.orchestration.connector.ConnectorConfig;
import org.nexus.gateway.orchestration.connector.ConnectorConfigService;
import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.connector.PspTargetPolicy;
import org.nexus.gateway.orchestration.connectors.DynamicHttpPspConnector;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.routing.RoutingEngine;
import org.nexus.gateway.orchestration.routing.RoutingRule;
import org.nexus.gateway.orchestration.service.OrchestrationService;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Unified Payment Orchestration API.
 * Single entry point for creating, querying, and managing orchestrated payments.
 *
 * <p>IDOR 加固（质量审查 Top2，2026-09-10）：支付 CRUD 四端点与
 * PaymentController 的 P0-4 模式对齐——create 从认证上下文取 merchantId
 * （不再信任请求体 merchant_id，原默认 "1" 使未认证调用即落入商户 1）；
 * get/refresh 校验支付归属；list 忽略 merchantId 请求参数、强制按
 * 认证商户过滤。routing-rules / connectors 为运营配置面（平台级），
 * 不做商户归属校验。</p>
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentOrchestrationController {

    private final OrchestrationService orchestrationService;
    private final ConnectorRegistry connectorRegistry;
    private final RoutingEngine routingEngine;
    private final MerchantOwnershipGuard ownershipGuard;

    /**
     * P0（2026-09-17）：动态 PSP 连接器的目标地址与凭据引用策略。
     * 负责拦截环境变量外泄与 SSRF，见 {@link PspTargetPolicy}。
     */
    private final PspTargetPolicy pspTargetPolicy;

    /**
     * 动态注册 Connector 配置服务 — 提供持久化 CRUD 和启动恢复。
     * 用于 wechat/alipay 类型的动态注册（http_psp 类型仍走原有逻辑以保持兼容）。
     */
    private final ConnectorConfigService connectorConfigService;

    public PaymentOrchestrationController(OrchestrationService orchestrationService,
                                          ConnectorRegistry connectorRegistry,
                                          RoutingEngine routingEngine,
                                          MerchantOwnershipGuard ownershipGuard,
                                          PspTargetPolicy pspTargetPolicy,
                                          ConnectorConfigService connectorConfigService) {
        this.orchestrationService = orchestrationService;
        this.connectorRegistry = connectorRegistry;
        this.routingEngine = routingEngine;
        this.ownershipGuard = ownershipGuard;
        this.pspTargetPolicy = pspTargetPolicy;
        this.connectorConfigService = connectorConfigService;
    }

    // === Payment CRUD ===

    @PostMapping
    public ResponseEntity<Map<String, Object>> createPayment(@RequestBody Map<String, Object> body,
                                                               HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
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
    public ResponseEntity<Map<String, Object>> getPayment(@PathVariable String id,
                                                            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        OrchestratedPayment payment = orchestrationService.getPayment(id);
        if (payment == null) {
            return ResponseEntity.notFound().build();
        }
        ownershipGuard.requireOwned(callerMerchantId, payment.getMerchantId(),
                "orchestrated_payment", null);
        return ResponseEntity.ok(toResponse(payment));
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listPayments(
            @RequestParam(defaultValue = "1") Long merchantId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest httpRequest) {
        // 归属过滤（Top2）：忽略调用方传入的 merchantId 参数，强制按认证商户查询。
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        Page<OrchestratedPayment> payments = orchestrationService.listPayments(callerMerchantId, status, page, limit);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("data", payments.getContent().stream().map(this::toResponse).collect(Collectors.toList()));
        resp.put("total", payments.getTotalElements());
        resp.put("page", page);
        resp.put("limit", limit);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/{id}/refresh")
    public ResponseEntity<Map<String, Object>> refreshStatus(@PathVariable String id,
                                                               HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        OrchestratedPayment payment = orchestrationService.refreshStatus(id);
        if (payment == null) return ResponseEntity.notFound().build();
        ownershipGuard.requireOwned(callerMerchantId, payment.getMerchantId(),
                "orchestrated_payment", null);
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

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/routing-rules")
    public ResponseEntity<RoutingRule> addRule(@RequestBody RoutingRule rule) {
        routingEngine.addRule(rule);
        return ResponseEntity.status(HttpStatus.CREATED).body(rule);
    }

    @PreAuthorize("hasRole('ADMIN')")
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

    @PreAuthorize("hasRole('ADMIN')")
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

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/connectors")
    public ResponseEntity<Map<String, Object>> registerConnector(@RequestBody Map<String, Object> body) {
        String id = body.get("id") == null ? null : String.valueOf(body.get("id")).trim();
        String type = body.get("type") == null ? null : String.valueOf(body.get("type")).trim();
        // 400：空 id 或非法 type
        if (id == null || id.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        // 支持的类型：http_psp, wechat, alipay
        if (!"http_psp".equals(type) && !"wechat".equals(type) && !"alipay".equals(type)) {
            return ResponseEntity.badRequest().build();
        }
        // 409：id 已存在（registry 中存在或本控制器已动态注册）
        if (connectorRegistry.get(id).isPresent() || dynamicConnectors.contains(id)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        String displayName = body.get("display_name") == null ? id : String.valueOf(body.get("display_name"));
        @SuppressWarnings("unchecked")
        Set<String> currencies = body.get("currencies") instanceof java.util.List
                ? new java.util.HashSet<>((java.util.List<String>) body.get("currencies"))
                : Set.of();
        int feeBps = body.get("fee_bps") instanceof Number ? ((Number) body.get("fee_bps")).intValue() : 0;

        if ("http_psp".equals(type)) {
            return registerHttpPspConnector(id, type, displayName, body, currencies, feeBps);
        } else if ("wechat".equals(type)) {
            return registerWeChatConnector(id, type, displayName, body, currencies, feeBps);
        } else {
            return registerAlipayConnector(id, type, displayName, body, currencies, feeBps);
        }
    }

    /**
     * 注册 http_psp 类型连接器（保持原有逻辑，使用 PspTargetPolicy 校验）。
     */
    private ResponseEntity<Map<String, Object>> registerHttpPspConnector(
            String id, String type, String displayName,
            Map<String, Object> body, Set<String> currencies, int feeBps) {
        String baseUrl = body.get("base_url") == null ? "" : String.valueOf(body.get("base_url"));
        if (!pspTargetPolicy.isAllowedBaseUrl(baseUrl)) {
            return ResponseEntity.badRequest().build();
        }
        String apiKeyEnv = body.get("api_key_env") == null ? null
                : String.valueOf(body.get("api_key_env")).trim();
        if (apiKeyEnv != null && !apiKeyEnv.isEmpty() && !pspTargetPolicy.isAllowedApiKeyEnv(apiKeyEnv)) {
            return ResponseEntity.badRequest().build();
        }

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
     * 注册 wechat 类型连接器（通过 ConnectorConfigService 持久化，跳过 PspTargetPolicy 校验）。
     *
     * <p>敏感信息策略：api_key_env 引用环境变量名，实际值通过 System.getenv() 解析。</p>
     */
    private ResponseEntity<Map<String, Object>> registerWeChatConnector(
            String id, String type, String displayName,
            Map<String, Object> body, Set<String> currencies, int feeBps) {
        String appId = body.get("app_id") == null ? "" : String.valueOf(body.get("app_id")).trim();
        String mchId = body.get("mch_id") == null ? "" : String.valueOf(body.get("mch_id")).trim();
        String apiKeyEnv = body.get("api_key_env") == null ? null
                : String.valueOf(body.get("api_key_env")).trim();

        ConnectorConfig config = new ConnectorConfig();
        config.setId(id);
        config.setType(type);
        config.setDisplayName(displayName);
        config.setAppId(appId);
        config.setMchId(mchId);
        config.setApiKeyEnv(apiKeyEnv);
        config.setCurrencies(String.join(",", currencies));
        config.setFeeBps(feeBps);
        config.setActive(true);

        try {
            connectorConfigService.registerConfig(config);
            dynamicConnectors.add(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("type", type);
        resp.put("status", "registered");
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * 注册 alipay 类型连接器（通过 ConnectorConfigService 持久化，跳过 PspTargetPolicy 校验）。
     *
     * <p>敏感信息策略：merchant_private_key_env 和 alipay_public_key_env 引用环境变量名，
     * 实际值通过 System.getenv() 解析。</p>
     */
    private ResponseEntity<Map<String, Object>> registerAlipayConnector(
            String id, String type, String displayName,
            Map<String, Object> body, Set<String> currencies, int feeBps) {
        String appId = body.get("app_id") == null ? "" : String.valueOf(body.get("app_id")).trim();
        String merchantPrivateKeyEnv = body.get("merchant_private_key_env") == null ? null
                : String.valueOf(body.get("merchant_private_key_env")).trim();
        String alipayPublicKeyEnv = body.get("alipay_public_key_env") == null ? null
                : String.valueOf(body.get("alipay_public_key_env")).trim();

        ConnectorConfig config = new ConnectorConfig();
        config.setId(id);
        config.setType(type);
        config.setDisplayName(displayName);
        config.setAppId(appId);
        config.setMerchantPrivateKey(merchantPrivateKeyEnv);
        config.setAlipayPublicKey(alipayPublicKeyEnv);
        config.setCurrencies(String.join(",", currencies));
        config.setFeeBps(feeBps);
        config.setActive(true);

        try {
            connectorConfigService.registerConfig(config);
            dynamicConnectors.add(id);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("id", id);
        resp.put("type", type);
        resp.put("status", "registered");
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    @PreAuthorize("hasRole('ADMIN')")
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
            // 同时从数据库删除持久化配置（wechat/alipay 类型）
            connectorConfigService.unregisterConfig(id);
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
        m.put("latency_ms", p.getLatencyMs());
        m.put("cost_bps", p.getCostBps());
        m.put("created_at", p.getCreatedAt() != null ? p.getCreatedAt().toString() : null);
        m.put("confirmed_at", p.getConfirmedAt() != null ? p.getConfirmedAt().toString() : null);
        m.put("expires_at", p.getExpiresAt() != null ? p.getExpiresAt().toString() : null);
        return m;
    }
}
