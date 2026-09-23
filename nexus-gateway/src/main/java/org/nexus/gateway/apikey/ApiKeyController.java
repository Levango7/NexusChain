package org.nexus.gateway.apikey;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API Key 管理 REST API。
 *
 * <p>提供 API Key 的创建、查询、轮换、撤销端点。所有端点需要租户上下文
 * （从请求属性 {@code nexus.tenantId} 获取，由 {@code TenantApiKeyInterceptor}
 * 在拦截器阶段填充）。</p>
 *
 * <p>路径前缀：{@code /api/v1/api-keys}</p>
 *
 * <p>安全说明：创建和轮换端点返回的明文 keySecret 仅出现一次，
 * 后续无法恢复，调用方必须立即安全存储。</p>
 */
@RestController
@RequestMapping("/api/v1/api-keys")
@Tag(name = "API Key", description = "API Key 生命周期管理：创建/查询/轮换/撤销")
public class ApiKeyController {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyController.class);

    /** 请求属性键：当前租户 ID（与 TenantApiKeyInterceptor.TENANT_ID_ATTR 一致）。 */
    private static final String TENANT_ID_ATTR = "nexus.tenantId";

    private final ApiKeyService apiKeyService;

    public ApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    /**
     * 创建 API Key — 返回明文 keySecret（仅此一次）。
     *
     * @param request HTTP 请求（用于获取租户上下文）
     * @param body    创建请求体
     * @return 201 + Key 信息（含明文 keySecret）
     */
    @Operation(summary = "Create a new API Key (returns plaintext secret once)")
    @PostMapping
    public ResponseEntity<Map<String, Object>> createApiKey(HttpServletRequest request,
                                                             @RequestBody CreateApiKeyRequest body) {
        String merchantId = requireTenantId(request);

        LocalDateTime expireAt = null;
        if (body.getExpireDays() != null) {
            expireAt = LocalDateTime.now().plusDays(body.getExpireDays());
        }

        ApiKeyService.CreateApiKeyResult result =
                apiKeyService.createApiKey(merchantId, body.getScopes(), body.getDescription(), expireAt);

        return ResponseEntity.status(HttpStatus.CREATED).body(result.toResponseMap());
    }

    /**
     * 列出当前租户的所有 API Key。
     *
     * @param request HTTP 请求
     * @return 200 + Key 列表（不含密钥）
     */
    @Operation(summary = "List all API Keys for the current tenant")
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listApiKeys(HttpServletRequest request) {
        String merchantId = requireTenantId(request);
        List<ApiKey> keys = apiKeyService.listApiKeys(merchantId);
        List<Map<String, Object>> result = keys.stream()
                .map(this::toSummaryMap)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * 查看单个 Key 详情。
     *
     * @param request HTTP 请求
     * @param keyId   Key 公开标识
     * @return 200 + Key 详情，或 404
     */
    @Operation(summary = "Get API Key details by keyId")
    @GetMapping("/{keyId}")
    public ResponseEntity<Map<String, Object>> getApiKey(HttpServletRequest request,
                                                          @PathVariable String keyId) {
        String merchantId = requireTenantId(request);
        List<ApiKey> keys = apiKeyService.listApiKeys(merchantId);
        return keys.stream()
                .filter(k -> k.getKeyId().equals(keyId))
                .findFirst()
                .map(k -> ResponseEntity.ok(toDetailMap(k)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 轮换 Key — 创建新 Key 替代旧 Key，返回新 Key 的明文密钥。
     *
     * @param request HTTP 请求
     * @param keyId   旧 Key 的公开标识
     * @return 201 + 新 Key 信息（含明文 keySecret）
     */
    @Operation(summary = "Rotate an API Key (creates new key, marks old as ROTATED)")
    @PostMapping("/{keyId}/rotate")
    public ResponseEntity<Map<String, Object>> rotateApiKey(HttpServletRequest request,
                                                             @PathVariable String keyId) {
        requireTenantId(request); // 确保租户上下文存在
        ApiKeyService.CreateApiKeyResult result = apiKeyService.rotateApiKey(keyId);
        return ResponseEntity.status(HttpStatus.CREATED).body(result.toResponseMap());
    }

    /**
     * 撤销 Key — 标记 REVOKED，不可恢复。
     *
     * @param request HTTP 请求
     * @param keyId   Key 的公开标识
     * @param body    撤销请求体（含 reason）
     * @return 200 + 更新后的 Key 信息
     */
    @Operation(summary = "Revoke an API Key (irreversible)")
    @PostMapping("/{keyId}/revoke")
    public ResponseEntity<Map<String, Object>> revokeApiKey(HttpServletRequest request,
                                                             @PathVariable String keyId,
                                                             @RequestBody(required = false) RevokeApiKeyRequest body) {
        requireTenantId(request);
        String reason = (body != null) ? body.getReason() : null;
        ApiKey revoked = apiKeyService.revokeApiKey(keyId, reason);
        return ResponseEntity.ok(toDetailMap(revoked));
    }

    // --- 内部方法 ---

    /**
     * 从请求属性获取租户 ID，不存在则抛异常。
     */
    private String requireTenantId(HttpServletRequest request) {
        Object tenantId = request.getAttribute(TENANT_ID_ATTR);
        if (tenantId == null) {
            throw new IllegalStateException("租户上下文未设置 — 请确保请求携带有效的 X-Tenant-Api-Key 头");
        }
        return tenantId.toString();
    }

    /**
     * 转换为摘要 Map（列表用，不含敏感字段）。
     */
    private Map<String, Object> toSummaryMap(ApiKey apiKey) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("keyId", apiKey.getKeyId());
        map.put("scopes", apiKey.getScopes());
        map.put("status", apiKey.getStatus().name());
        map.put("expireAt", apiKey.getExpireAt());
        map.put("description", apiKey.getDescription());
        map.put("createdAt", apiKey.getCreatedAt());
        map.put("lastUsedAt", apiKey.getLastUsedAt());
        return map;
    }

    /**
     * 转换为详情 Map（单个 Key 详情，不含密钥哈希）。
     */
    private Map<String, Object> toDetailMap(ApiKey apiKey) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("keyId", apiKey.getKeyId());
        map.put("merchantId", apiKey.getMerchantId());
        map.put("scopes", apiKey.getScopes());
        map.put("status", apiKey.getStatus().name());
        map.put("expireAt", apiKey.getExpireAt());
        map.put("rotatedFromId", apiKey.getRotatedFromId());
        map.put("createdAt", apiKey.getCreatedAt());
        map.put("lastUsedAt", apiKey.getLastUsedAt());
        map.put("revokedAt", apiKey.getRevokedAt());
        map.put("revokedReason", apiKey.getRevokedReason());
        map.put("description", apiKey.getDescription());
        return map;
    }

    // --- DTO ---

    /**
     * 创建 API Key 请求体。
     */
    public static class CreateApiKeyRequest {
        /** 权限范围（逗号分隔的 ApiKeyScope 名称，如 "PAYMENTS,REFUNDS"）。 */
        private String scopes;

        /** Key 用途描述。 */
        private String description;

        /** 过期天数（null 表示永不过期）。 */
        private Integer expireDays;

        public String getScopes() { return scopes; }
        public void setScopes(String scopes) { this.scopes = scopes; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public Integer getExpireDays() { return expireDays; }
        public void setExpireDays(Integer expireDays) { this.expireDays = expireDays; }
    }

    /**
     * 撤销 API Key 请求体。
     */
    public static class RevokeApiKeyRequest {
        /** 撤销原因。 */
        private String reason;

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }
}