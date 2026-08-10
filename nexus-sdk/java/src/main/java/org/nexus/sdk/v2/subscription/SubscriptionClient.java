package org.nexus.sdk.v2.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nexus.sdk.v2.CursorPage;
import org.nexus.sdk.v2.V2ApiException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * 订阅管理客户端（P4-T9）。
 *
 * <p>对接 NexusChain gateway 的订阅管理 API，支持订阅的创建、查询、取消、
 * 升级、降级、计划列出与使用量查询。</p>
 *
 * <h3>对接端点</h3>
 * <ul>
 *   <li>{@code POST   /api/v2/subscriptions} — 创建订阅</li>
 *   <li>{@code GET    /api/v2/subscriptions/{id}} — 查询订阅</li>
 *   <li>{@code DELETE /api/v2/subscriptions/{id}} — 取消订阅</li>
 *   <li>{@code POST   /api/v2/subscriptions/{id}/upgrade} — 升级订阅</li>
 *   <li>{@code POST   /api/v2/subscriptions/{id}/downgrade} — 降级订阅</li>
 *   <li>{@code GET    /api/v2/subscriptions/plans} — 列出订阅计划（游标分页）</li>
 *   <li>{@code GET    /api/v2/subscriptions/{id}/usage} — 查询使用量</li>
 * </ul>
 *
 * <pre>{@code
 * SubscriptionClient client = new SubscriptionClient("http://localhost:8080", "apiKey");
 * JsonNode sub = client.createSubscription("plan-pro", "cust-123", "pm-token-abc");
 * String subId = sub.get("subscriptionId").asText();
 * client.upgradeSubscription(subId, "plan-enterprise");
 * JsonNode usage = client.getUsage(subId, "2026-01-01", "2026-02-01");
 * }</pre>
 *
 * @see org.nexus.sdk.v2.NexusChainV2Client#subscriptions()
 */
public class SubscriptionClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** API 版本头名 */
    public static final String VERSION_HEADER = "X-NexusChain-API-Version";

    /** API Key 头名 */
    public static final String API_KEY_HEADER = "X-NexusChain-ApiKey";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    /**
     * 构造订阅管理客户端。
     *
     * @param baseUrl 网关基础 URL
     * @param apiKey  商户 API Key
     */
    public SubscriptionClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null);
    }

    /**
     * 构造订阅管理客户端（复用外部 HttpClient）。
     */
    public SubscriptionClient(String baseUrl, String apiKey, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // === 公共 API ===

    /**
     * 创建订阅。
     *
     * @param planId             订阅计划 ID
     * @param customerId         客户 ID
     * @param paymentMethodToken 支付方式 token（由支付网关颁发）
     * @return 订阅创建结果 JSON（含 subscriptionId、status、currentPeriodEnd 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode createSubscription(String planId, String customerId, String paymentMethodToken) {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(paymentMethodToken, "paymentMethodToken");
        Map<String, Object> body = new HashMap<>();
        body.put("planId", planId);
        body.put("customerId", customerId);
        body.put("paymentMethodToken", paymentMethodToken);
        HttpResponse<String> resp = post("/api/v2/subscriptions", toJson(body));
        return parseJson(resp.body());
    }

    /**
     * 查询订阅。
     *
     * @param subscriptionId 订阅 ID
     * @return 订阅详情 JSON
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode getSubscription(String subscriptionId) {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        if (subscriptionId.isEmpty()) {
            throw new V2ApiException("subscriptionId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        HttpResponse<String> resp = get("/api/v2/subscriptions/" + subscriptionId);
        return parseJson(resp.body());
    }

    /**
     * 取消订阅。
     *
     * @param subscriptionId 订阅 ID
     * @param reason         取消原因（可 null）
     * @return 取消结果 JSON（含 canceledAt、status 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode cancelSubscription(String subscriptionId, String reason) {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        if (subscriptionId.isEmpty()) {
            throw new V2ApiException("subscriptionId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        String query = buildQuery("reason", reason);
        HttpResponse<String> resp = delete("/api/v2/subscriptions/" + subscriptionId + query);
        return parseJson(resp.body());
    }

    /**
     * 升级订阅。
     *
     * @param subscriptionId 订阅 ID
     * @param newPlanId      新计划 ID（必须高于当前计划等级）
     * @return 升级结果 JSON（含 newPlanId、prorationAmount、effectiveAt 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode upgradeSubscription(String subscriptionId, String newPlanId) {
        return changePlan(subscriptionId, newPlanId, "/upgrade");
    }

    /**
     * 降级订阅。
     *
     * @param subscriptionId 订阅 ID
     * @param newPlanId      新计划 ID（必须低于当前计划等级）
     * @return 降级结果 JSON（含 newPlanId、prorationAmount、effectiveAt 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode downgradeSubscription(String subscriptionId, String newPlanId) {
        return changePlan(subscriptionId, newPlanId, "/downgrade");
    }

    private JsonNode changePlan(String subscriptionId, String newPlanId, String action) {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(newPlanId, "newPlanId");
        if (subscriptionId.isEmpty()) {
            throw new V2ApiException("subscriptionId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("newPlanId", newPlanId);
        HttpResponse<String> resp = post("/api/v2/subscriptions/" + subscriptionId + action, toJson(body));
        return parseJson(resp.body());
    }

    /**
     * 列出订阅计划（游标分页）。
     *
     * @param cursor   游标（首页传 null）
     * @param pageSize 每页条数（&lt;=0 取服务端默认 20）
     * @return 游标分页结果
     * @throws V2ApiException 当服务端返回错误时
     */
    public CursorPage<JsonNode> listPlans(String cursor, int pageSize) {
        String query = buildQuery(
                "cursor", cursor,
                "pageSize", pageSize > 0 ? String.valueOf(pageSize) : null
        );
        HttpResponse<String> resp = get("/api/v2/subscriptions/plans" + query);
        return parseCursorPage(resp.body());
    }

    /**
     * 查询使用量。
     *
     * @param subscriptionId 订阅 ID
     * @param periodStart    周期开始（ISO-8601，如 "2026-01-01"）
     * @param periodEnd      周期结束（ISO-8601，如 "2026-02-01"）
     * @return 使用量 JSON（含 apiCalls、storageBytes、bandwidthBytes 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode getUsage(String subscriptionId, String periodStart, String periodEnd) {
        Objects.requireNonNull(subscriptionId, "subscriptionId");
        Objects.requireNonNull(periodStart, "periodStart");
        Objects.requireNonNull(periodEnd, "periodEnd");
        if (subscriptionId.isEmpty()) {
            throw new V2ApiException("subscriptionId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        String query = buildQuery("periodStart", periodStart, "periodEnd", periodEnd);
        HttpResponse<String> resp = get("/api/v2/subscriptions/" + subscriptionId + "/usage" + query);
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
     * 构造 DELETE 请求（包内可见，供测试验证）。
     */
    HttpRequest buildDeleteRequest(String path) {
        return buildRequest("DELETE", path, null);
    }

    /** 暴露 baseUrl（供测试验证 URL 构造） */
    String baseUrl() {
        return baseUrl;
    }

    /** 暴露 apiKey（供测试验证头注入） */
    String apiKey() {
        return apiKey;
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

    private HttpResponse<String> delete(String path) {
        return send("DELETE", path, null);
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
        if ("GET".equals(method)) {
            builder.GET();
        } else if ("POST".equals(method)) {
            builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        } else if ("DELETE".equals(method)) {
            builder.DELETE();
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

    private CursorPage<JsonNode> parseCursorPage(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<JsonNode> data = new ArrayList<>();
            JsonNode dataNode = root.get("data");
            if (dataNode != null && dataNode.isArray()) {
                dataNode.forEach(data::add);
            }
            JsonNode nextCursorNode = root.get("nextCursor");
            String nextCursor = (nextCursorNode != null && !nextCursorNode.isNull())
                    ? nextCursorNode.asText() : null;
            boolean hasMore = root.has("hasMore") && root.get("hasMore").asBoolean();
            int count = root.has("count") ? root.get("count").asInt() : data.size();
            int pageSize = root.has("pageSize") ? root.get("pageSize").asInt() : data.size();
            return new CursorPage<>(data, nextCursor, hasMore, count, pageSize);
        } catch (Exception e) {
            throw new V2ApiException("Failed to parse cursor page: " + e.getMessage(),
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