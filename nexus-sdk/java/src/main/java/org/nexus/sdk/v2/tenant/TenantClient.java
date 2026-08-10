package org.nexus.sdk.v2.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nexus.sdk.v2.V2ApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 多租户管理客户端（P4-T9）。
 *
 * <p>对接 NexusChain gateway 的多租户管理 API，支持租户的创建、查询、更新、
 * 暂停、激活、使用量查询与限流状态查询。</p>
 *
 * <h3>对接端点</h3>
 * <ul>
 *   <li>{@code POST  /api/v2/tenants} — 创建租户</li>
 *   <li>{@code GET   /api/v2/tenants/{id}} — 查询租户</li>
 *   <li>{@code PATCH /api/v2/tenants/{id}} — 更新租户配置</li>
 *   <li>{@code POST  /api/v2/tenants/{id}/suspend} — 暂停租户</li>
 *   <li>{@code POST  /api/v2/tenants/{id}/reactivate} — 激活租户</li>
 *   <li>{@code GET   /api/v2/tenants/{id}/usage} — 查询使用量</li>
 *   <li>{@code GET   /api/v2/tenants/{id}/rate-limit-status} — 查询限流状态</li>
 * </ul>
 *
 * <h3>租户身份认证</h3>
 * <p>本客户端支持 {@code X-Tenant-Api-Key} 头注入，用于租户级 API Key 认证。
 * 构造时可传入 {@code tenantApiKey}，所有请求将自动注入该头。
 * 若同时传入商户 API Key（{@code apiKey}），两个头都会注入。</p>
 *
 * <pre>{@code
 * // 平台管理员视角（使用商户 API Key）
 * TenantClient adminClient = new TenantClient("http://localhost:8080", "adminApiKey", null);
 * JsonNode tenant = adminClient.createTenant("Acme Corp", "admin@acme.com", "enterprise");
 *
 * // 租户视角（使用租户 API Key）
 * TenantClient tenantClient = new TenantClient("http://localhost:8080", null, "tenantApiKey");
 * JsonNode usage = tenantClient.getUsage("tenant-123", "2026-01-01", "2026-02-01");
 * }</pre>
 *
 * @see org.nexus.sdk.v2.NexusChainV2Client#tenants()
 */
public class TenantClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** API 版本头名 */
    public static final String VERSION_HEADER = "X-NexusChain-API-Version";

    /** API Key 头名（商户级） */
    public static final String API_KEY_HEADER = "X-NexusChain-ApiKey";

    /** 租户 API Key 头名（租户级身份认证） */
    public static final String TENANT_API_KEY_HEADER = "X-Tenant-Api-Key";

    private final String baseUrl;
    private final String apiKey;
    private final String tenantApiKey;
    private final HttpClient httpClient;

    /**
     * 构造租户管理客户端（仅商户 API Key）。
     *
     * @param baseUrl 网关基础 URL
     * @param apiKey  商户 API Key（可 null）
     */
    public TenantClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null, null);
    }

    /**
     * 构造租户管理客户端（含租户 API Key）。
     *
     * @param baseUrl      网关基础 URL
     * @param apiKey       商户 API Key（可 null）
     * @param tenantApiKey 租户 API Key（可 null；非 null 时注入 X-Tenant-Api-Key 头）
     */
    public TenantClient(String baseUrl, String apiKey, String tenantApiKey) {
        this(baseUrl, apiKey, tenantApiKey, null);
    }

    /**
     * 构造租户管理客户端（复用外部 HttpClient）。
     *
     * @param baseUrl      网关基础 URL
     * @param apiKey       商户 API Key（可 null）
     * @param tenantApiKey 租户 API Key（可 null）
     * @param httpClient   外部 HttpClient（null 则内部新建）
     */
    public TenantClient(String baseUrl, String apiKey, String tenantApiKey, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.tenantApiKey = tenantApiKey;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // === 公共 API ===

    /**
     * 创建租户。
     *
     * @param name      租户名称
     * @param adminEmail 管理员邮箱
     * @param plan      订阅计划（如 "free"、"pro"、"enterprise"）
     * @return 租户创建结果 JSON（含 tenantId、apiKey、status 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode createTenant(String name, String adminEmail, String plan) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(adminEmail, "adminEmail");
        Objects.requireNonNull(plan, "plan");
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("adminEmail", adminEmail);
        body.put("plan", plan);
        HttpResponse<String> resp = post("/api/v2/tenants", toJson(body));
        return parseJson(resp.body());
    }

    /**
     * 查询租户。
     *
     * @param tenantId 租户 ID
     * @return 租户详情 JSON（含 name、plan、status、createdAt 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode getTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (tenantId.isEmpty()) {
            throw new V2ApiException("tenantId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        HttpResponse<String> resp = get("/api/v2/tenants/" + tenantId);
        return parseJson(resp.body());
    }

    /**
     * 更新租户配置。
     *
     * @param tenantId 租户 ID
     * @param updates  更新字段（键名与 OpenAPI schema 一致，如 "name"、"plan"、"settings"）
     * @return 更新后的租户 JSON
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode updateTenant(String tenantId, Map<String, Object> updates) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(updates, "updates");
        if (tenantId.isEmpty()) {
            throw new V2ApiException("tenantId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        if (updates.isEmpty()) {
            throw new V2ApiException("updates must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        HttpResponse<String> resp = patch("/api/v2/tenants/" + tenantId, toJson(updates));
        return parseJson(resp.body());
    }

    /**
     * 暂停租户。
     *
     * @param tenantId 租户 ID
     * @param reason   暂停原因（可 null）
     * @return 暂停结果 JSON（含 suspendedAt、status 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode suspendTenant(String tenantId, String reason) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (tenantId.isEmpty()) {
            throw new V2ApiException("tenantId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        Map<String, Object> body = new HashMap<>();
        if (reason != null) body.put("reason", reason);
        HttpResponse<String> resp = post("/api/v2/tenants/" + tenantId + "/suspend", toJson(body));
        return parseJson(resp.body());
    }

    /**
     * 激活租户。
     *
     * @param tenantId 租户 ID
     * @return 激活结果 JSON（含 reactivatedAt、status 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode reactivateTenant(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (tenantId.isEmpty()) {
            throw new V2ApiException("tenantId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        HttpResponse<String> resp = post("/api/v2/tenants/" + tenantId + "/reactivate", "{}");
        return parseJson(resp.body());
    }

    /**
     * 查询租户使用量。
     *
     * @param tenantId    租户 ID
     * @param periodStart 周期开始（ISO-8601）
     * @param periodEnd   周期结束（ISO-8601）
     * @return 使用量 JSON（含 apiCalls、storageBytes、bandwidthBytes 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode getUsage(String tenantId, String periodStart, String periodEnd) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (tenantId.isEmpty()) {
            throw new V2ApiException("tenantId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        String query = buildQuery("periodStart", periodStart, "periodEnd", periodEnd);
        HttpResponse<String> resp = get("/api/v2/tenants/" + tenantId + "/usage" + query);
        return parseJson(resp.body());
    }

    /**
     * 查询限流状态。
     *
     * @param tenantId 租户 ID
     * @return 限流状态 JSON（含 currentRate、limit、resetAt 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode getRateLimitStatus(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (tenantId.isEmpty()) {
            throw new V2ApiException("tenantId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        HttpResponse<String> resp = get("/api/v2/tenants/" + tenantId + "/rate-limit-status");
        return parseJson(resp.body());
    }

    // === 包内可见的 HTTP 工具（供测试验证请求构造） ===

    /**
     * 构造 GET 请求（包内可见，供测试验证）。
     */
    HttpRequest buildGetRequest(String path) {
        return buildRequest("GET", path, null);
    }

    /**
     * 构造 POST 请求（包内可见，供测试验证）。
     */
    HttpRequest buildPostRequest(String path, String body) {
        return buildRequest("POST", path, body);
    }

    /**
     * 构造 PATCH 请求（包内可见，供测试验证）。
     */
    HttpRequest buildPatchRequest(String path, String body) {
        return buildRequest("PATCH", path, body);
    }

    /** 暴露 baseUrl（供测试验证 URL 构造） */
    String baseUrl() {
        return baseUrl;
    }

    /** 暴露 apiKey（供测试验证头注入） */
    String apiKey() {
        return apiKey;
    }

    /** 暴露 tenantApiKey（供测试验证头注入） */
    String tenantApiKey() {
        return tenantApiKey;
    }

    /** 包内可见：构造查询字符串（供测试验证） */
    String buildQueryPublic(String... kv) {
        return buildQuery(kv);
    }

    // === 内部 HTTP 工具 ===

    private HttpResponse<String> get(String path) {
        return send("GET", path, null);
    }

    private HttpResponse<String> post(String path, String body) {
        return send("POST", path, body);
    }

    private HttpResponse<String> patch(String path, String body) {
        return send("PATCH", path, body);
    }

    private HttpResponse<String> send(String method, String path, String body) {
        try {
            HttpRequest request = buildRequest(method, path, body);
            HttpResponse<String> resp = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw V2ApiException.fromResponse(resp.statusCode(), resp.body());
            }
            return resp;
        } catch (V2ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new V2ApiException("HTTP " + method + " " + path + " failed: " + e.getMessage(),
                    0, "HTTP_ERROR", null, e);
        }
    }

    private HttpRequest buildRequest(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header(VERSION_HEADER, "2")
                .timeout(Duration.ofSeconds(30));
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header(API_KEY_HEADER, apiKey);
        }
        if (tenantApiKey != null && !tenantApiKey.isEmpty()) {
            builder.header(TENANT_API_KEY_HEADER, tenantApiKey);
        }
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        } else if ("PATCH".equals(method)) {
            builder.method("PATCH", HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        }
        return builder.build();
    }

    private JsonNode parseJson(String json) {
        try {
            return MAPPER.readTree(json == null ? "{}" : json);
        } catch (Exception e) {
            throw new V2ApiException("Failed to parse response JSON: " + e.getMessage(),
                    0, "PARSE_ERROR", null, e);
        }
    }

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new V2ApiException("Failed to serialize request: " + e.getMessage(),
                    0, "SERIALIZE_ERROR", null, e);
        }
    }

    private String buildQuery(String... kv) {
        StringJoiner sj = new StringJoiner("&", "?", "");
        sj.setEmptyValue("");
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null && !kv[i + 1].isEmpty()) {
                sj.add(kv[i] + "=" + kv[i + 1]);
            }
        }
        String result = sj.toString();
        return result.isEmpty() ? "" : result;
    }
}