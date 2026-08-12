package org.nexus.sdk.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nexus.sdk.v2.avalanche.AvalancheClient;
import org.nexus.sdk.v2.crosschain.CrossChainMessageClient;
import org.nexus.sdk.v2.solana.SolanaClient;
import org.nexus.sdk.v2.subscription.SubscriptionClient;
import org.nexus.sdk.v2.tenant.TenantClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * NexusChain v2 API 客户端（P4-T7）。
 *
 * <p>相比 v1 客户端的增强：</p>
 * <ul>
 *   <li>游标分页：{@link #listOrders(String, Integer, String, Long)} 返回 {@link CursorPage}</li>
 *   <li>字段筛选：所有查询方法支持 {@code fields} 参数</li>
 *   <li>批量操作：{@link #batchCreatePayments(List, String)} 一次提交多笔支付</li>
 *   <li>统一错误处理：错误响应自动解析为 {@link V2ApiException}</li>
 *   <li>API 版本协商：自动注入 {@code X-NexusChain-API-Version: 2} 头</li>
 * </ul>
 *
 * <pre>{@code
 * NexusChainV2Client client = new NexusChainV2Client(
 *     "http://localhost:8080", "your-api-key");
 *
 * // 游标分页 + 字段筛选
 * CursorPage<JsonNode> page = client.listOrders(null, 20, "id,amount,status", 100L);
 * while (page.hasMore()) {
 *     page = client.listOrders(page.nextCursor(), 20, "id,amount,status", 100L);
 * }
 *
 * // 批量创建支付
 * List<PaymentItem> items = ...;
 * BatchResult result = client.batchCreatePayments(items, "ALL_OR_NOTHING");
 * }</pre>
 */
public class NexusChainV2Client {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** API 版本头名 */
    public static final String VERSION_HEADER = "X-NexusChain-API-Version";

    /** API Key 头名 */
    public static final String API_KEY_HEADER = "X-NexusChain-ApiKey";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    /**
     * 构造 v2 客户端。
     *
     * @param baseUrl 网关基础 URL（如 "http://localhost:8080"）
     * @param apiKey  商户 API Key
     */
    public NexusChainV2Client(String baseUrl, String apiKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // === 订单 ===

    /**
     * 列出订单（游标分页 + 字段筛选）。
     *
     * @param cursor     游标（首页传 null）
     * @param pageSize   每页条数（null 取服务端默认 20）
     * @param fields     字段筛选（如 "id,amount,status"；null 返回全部字段）
     * @param merchantId 商户 ID 过滤（null 不过滤）
     * @return 游标分页结果
     */
    public CursorPage<JsonNode> listOrders(String cursor, Integer pageSize,
                                            String fields, Long merchantId) {
        String query = buildQuery(
                "cursor", cursor,
                "pageSize", pageSize == null ? null : String.valueOf(pageSize),
                "fields", fields,
                "merchantId", merchantId == null ? null : String.valueOf(merchantId)
        );
        HttpResponse<String> resp = get("/api/v2/orders" + query);
        return parseCursorPage(resp.body());
    }

    /**
     * 查询订单详情（支持字段筛选）。
     *
     * @param id     订单 ID
     * @param fields 字段筛选（null 返回全部字段）
     * @return 订单 JSON 节点
     */
    public JsonNode getOrder(long id, String fields) {
        String query = buildQuery("fields", fields);
        HttpResponse<String> resp = get("/api/v2/orders/" + id + query);
        return parseJson(resp.body());
    }

    /**
     * 查询订单的支付最终性状态（NexFinality 网关侧原型）。
     *
     * <p>商户用于按结算金额决定发货时机：大额等 {@code FINALIZED}（不可逆），
     * 小额可在 {@code OPTIMISTIC}（已入块）即发货。</p>
     *
     * @param orderId 订单 ID
     * @return 最终性信息 JSON（含 finality_status/confirmations/threshold/progress_percent）
     */
    public JsonNode getOrderFinality(long orderId) {
        HttpResponse<String> resp = get("/api/v2/orders/" + orderId + "/finality");
        return parseJson(resp.body());
    }

    /**
     * 创建订单。
     *
     * @param request 订单创建请求（Map 形式，键名与 OpenAPI schema 一致）
     * @return 创建的订单 JSON 节点
     */
    public JsonNode createOrder(Map<String, Object> request) {
        HttpResponse<String> resp = post("/api/v2/orders", toJson(request));
        return parseJson(resp.body());
    }

    /**
     * 发起支付。
     *
     * @param orderId       订单 ID
     * @param payerAddress  付款方钱包地址
     * @return 支付结果 JSON 节点
     */
    public JsonNode pay(long orderId, String payerAddress) {
        Map<String, Object> body = new HashMap<>();
        body.put("payerAddress", payerAddress);
        HttpResponse<String> resp = post("/api/v2/orders/" + orderId + "/pay", toJson(body));
        return parseJson(resp.body());
    }

    /**
     * 退款。
     *
     * @param orderId 订单 ID
     * @param amount  退款金额
     * @param reason  退款原因（可 null）
     * @return 退款结果 JSON 节点
     */
    public JsonNode refund(long orderId, java.math.BigDecimal amount, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        if (reason != null) body.put("reason", reason);
        HttpResponse<String> resp = post("/api/v2/orders/" + orderId + "/refund", toJson(body));
        return parseJson(resp.body());
    }

    // === 批量支付 ===

    /**
     * 批量创建支付。
     *
     * @param items     支付项列表（最多 50 项）
     * @param onFailure 失败策略（"ALL_OR_NOTHING" 或 "PARTIAL"；null 取默认）
     * @return 批量结果
     */
    public BatchResult batchCreatePayments(List<PaymentItem> items, String onFailure) {
        Map<String, Object> body = new HashMap<>();
        body.put("payments", items);
        if (onFailure != null) body.put("onFailure", onFailure);
        HttpResponse<String> resp = post("/api/v2/payments/batch", toJson(body));
        return parseBatchResult(resp.body());
    }

    // === 商户 ===

    /**
     * 注册商户。
     */
    public JsonNode registerMerchant(String merchantName, String email, String settlementAddress) {
        Map<String, Object> body = new HashMap<>();
        body.put("merchantName", merchantName);
        body.put("email", email);
        body.put("settlementAddress", settlementAddress);
        HttpResponse<String> resp = post("/api/v2/merchants/register", toJson(body));
        return parseJson(resp.body());
    }

    /**
     * 查询商户。
     */
    public JsonNode getMerchant(long merchantId) {
        HttpResponse<String> resp = get("/api/v2/merchants/" + merchantId);
        return parseJson(resp.body());
    }

    // === HTTP 工具 ===

    private HttpResponse<String> get(String path) {
        return send("GET", path, null);
    }

    private HttpResponse<String> post(String path, String body) {
        return send("POST", path, body);
    }

    private HttpResponse<String> send(String method, String path, String body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header(VERSION_HEADER, "2")  // v2 版本协商
                    .timeout(Duration.ofSeconds(30));
            if (apiKey != null && !apiKey.isEmpty()) {
                builder.header(API_KEY_HEADER, apiKey);
            }
            if ("GET".equals(method)) {
                builder.GET();
            } else if ("POST".equals(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
            }
            HttpResponse<String> resp = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            // 错误响应检测
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

    // === 解析工具 ===

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

    private BatchResult parseBatchResult(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            List<BatchResult.Succeeded> succeeded = new ArrayList<>();
            List<BatchResult.Failed> failed = new ArrayList<>();
            JsonNode succNode = root.get("succeeded");
            if (succNode != null && succNode.isArray()) {
                for (JsonNode n : succNode) {
                    succeeded.add(new BatchResult.Succeeded(
                            n.get("index").asInt(),
                            n.get("id").asLong(),
                            n.get("orderNo").asText(),
                            n.get("status").asText()));
                }
            }
            JsonNode failNode = root.get("failed");
            if (failNode != null && failNode.isArray()) {
                for (JsonNode n : failNode) {
                    JsonNode err = n.get("error");
                    failed.add(new BatchResult.Failed(
                            n.get("index").asInt(),
                            err.get("code").asText(),
                            err.get("message").asText()));
                }
            }
            int total = root.has("totalCount") ? root.get("totalCount").asInt() : succeeded.size() + failed.size();
            return new BatchResult(succeeded, failed, total);
        } catch (Exception e) {
            throw new V2ApiException("Failed to parse batch result: " + e.getMessage(),
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

    // === Phase 4 子客户端便捷入口（P4-T9） ===

    /**
     * 获取 Solana 链操作客户端。
     *
     * <p>返回的客户端复用本客户端的 baseUrl 与 apiKey。</p>
     *
     * @return Solana 客户端实例
     * @since 2.0.0
     */
    public SolanaClient solana() {
        return new SolanaClient(baseUrl, apiKey, httpClient);
    }

    /**
     * 获取 Avalanche C-Chain 操作客户端。
     *
     * <p>返回的客户端复用本客户端的 baseUrl 与 apiKey。</p>
     *
     * @return Avalanche 客户端实例
     * @since 2.0.0
     */
    public AvalancheClient avalanche() {
        return new AvalancheClient(baseUrl, apiKey, httpClient);
    }

    /**
     * 获取跨链消息传递客户端。
     *
     * <p>返回的客户端复用本客户端的 baseUrl 与 apiKey。</p>
     *
     * @return 跨链消息客户端实例
     * @since 2.0.0
     */
    public CrossChainMessageClient crossChain() {
        return new CrossChainMessageClient(baseUrl, apiKey, httpClient);
    }

    /**
     * 获取订阅管理客户端。
     *
     * <p>返回的客户端复用本客户端的 baseUrl 与 apiKey。</p>
     *
     * @return 订阅管理客户端实例
     * @since 2.0.0
     */
    public SubscriptionClient subscriptions() {
        return new SubscriptionClient(baseUrl, apiKey, httpClient);
    }

    /**
     * 获取多租户管理客户端。
     *
     * <p>返回的客户端复用本客户端的 baseUrl 与 apiKey（商户级）。
     * 若需要租户级身份认证，请通过 {@link TenantClient#TenantClient(String, String, String)}
     * 单独构造并传入 {@code tenantApiKey}。</p>
     *
     * @return 多租户管理客户端实例
     * @since 2.0.0
     */
    public TenantClient tenants() {
        return new TenantClient(baseUrl, apiKey, null, httpClient);
    }
}