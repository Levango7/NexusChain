package org.nexus.gateway.tenant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 租户管理 REST API（P4-T6 多租户改造）。
 *
 * <p>提供租户 CRUD、状态流转（暂停/终止/恢复）和配额查询/更新端点。
 * 路径前缀 {@code /api/v2/tenants}，与 v2 API 规范对齐。</p>
 *
 * <p>注意：本控制器不经过 {@link TenantApiKeyInterceptor}（在 {@code WebConfig}
 * 中排除），由 admin 鉴权层保护。租户管理操作属于平台运营动作，不应受租户自身 API Key 限制。</p>
 */
@RestController
@RequestMapping("/api/v2/tenants")
@Tag(name = "Tenant", description = "多租户管理 API：CRUD + 配额 + 状态流转")
public class TenantController {

    private static final Logger log = LoggerFactory.getLogger(TenantController.class);

    private final TenantService tenantService;

    public TenantController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    /**
     * 创建租户。
     *
     * @param request 创建请求（name、config 可选；tenantId/apiKey/apiSecret 服务端生成）
     * @return 创建的租户（201）
     */
    @Operation(summary = "Create a new tenant")
    @PostMapping
    public ResponseEntity<Tenant> createTenant(@RequestBody CreateTenantRequest request) {
        Tenant tenant = new Tenant();
        tenant.setName(request.getName());
        if (request.getConfig() != null) {
            tenant.setConfig(request.getConfig());
        }
        Tenant created = tenantService.createTenant(tenant);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * 查询租户详情。
     *
     * @param tenantId 业务租户 ID
     * @return 租户实体（200）或 404
     */
    @Operation(summary = "Get tenant by tenantId")
    @GetMapping("/{tenantId}")
    public ResponseEntity<Tenant> getTenant(@PathVariable String tenantId) {
        return tenantService.getTenant(tenantId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 更新租户基本信息与配置。
     *
     * @param tenantId 业务租户 ID
     * @param request  更新请求
     * @return 更新后的租户
     */
    @Operation(summary = "Update tenant name and config")
    @PutMapping("/{tenantId}")
    public ResponseEntity<Tenant> updateTenant(@PathVariable String tenantId,
                                                @RequestBody CreateTenantRequest request) {
        Tenant updated = new Tenant();
        updated.setName(request.getName());
        if (request.getConfig() != null) {
            updated.setConfig(request.getConfig());
        }
        return ResponseEntity.ok(tenantService.updateTenant(tenantId, updated));
    }

    /**
     * 查询租户配额（限流 + 费率）。
     *
     * @param tenantId 业务租户 ID
     * @return 配额信息
     */
    @Operation(summary = "Get tenant quota (rate limit + fee rate)")
    @GetMapping("/{tenantId}/quota")
    public ResponseEntity<Map<String, Object>> getQuota(@PathVariable String tenantId) {
        Tenant tenant = tenantService.getTenant(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        TenantConfig cfg = tenant.getConfig();
        Map<String, Object> quota = new HashMap<>();
        quota.put("tenantId", tenantId);
        quota.put("rateLimitPerSecond", cfg.getRateLimitPerSecond());
        quota.put("rateLimitPerMinute", cfg.getRateLimitPerMinute());
        quota.put("maxPaymentAmount", cfg.getMaxPaymentAmount());
        quota.put("allowedCurrencies", cfg.getAllowedCurrencies());
        quota.put("feeRateBps", cfg.getFeeRateBps());
        return ResponseEntity.ok(quota);
    }

    /**
     * 更新租户配额。
     *
     * @param tenantId 业务租户 ID
     * @param config   新配额配置
     * @return 更新后的租户
     */
    @Operation(summary = "Update tenant quota (rate limit + fee rate)")
    @PutMapping("/{tenantId}/quota")
    public ResponseEntity<Tenant> updateQuota(@PathVariable String tenantId,
                                                @RequestBody TenantConfig config) {
        return ResponseEntity.ok(tenantService.updateTenantConfig(tenantId, config));
    }

    /**
     * 暂停租户。
     *
     * @param tenantId 业务租户 ID
     * @return 更新后的租户
     */
    @Operation(summary = "Suspend tenant")
    @PostMapping("/{tenantId}/suspend")
    public ResponseEntity<Tenant> suspend(@PathVariable String tenantId) {
        return ResponseEntity.ok(tenantService.suspendTenant(tenantId));
    }

    /**
     * 恢复租户。
     *
     * @param tenantId 业务租户 ID
     * @return 更新后的租户
     */
    @Operation(summary = "Activate suspended tenant")
    @PostMapping("/{tenantId}/activate")
    public ResponseEntity<Tenant> activate(@PathVariable String tenantId) {
        return ResponseEntity.ok(tenantService.activateTenant(tenantId));
    }

    /**
     * 终止租户（终态，不可恢复）。
     *
     * @param tenantId 业务租户 ID
     * @return 更新后的租户
     */
    @Operation(summary = "Terminate tenant (irreversible)")
    @PostMapping("/{tenantId}/terminate")
    public ResponseEntity<Tenant> terminate(@PathVariable String tenantId) {
        return ResponseEntity.ok(tenantService.terminateTenant(tenantId));
    }

    /**
     * 创建租户请求 DTO。
     */
    public static class CreateTenantRequest {
        private String name;
        private TenantConfig config;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public TenantConfig getConfig() { return config; }
        public void setConfig(TenantConfig config) { this.config = config; }
    }
}