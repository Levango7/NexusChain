package org.nexus.sdk.v2.crosschain;

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
 * 跨链消息传递客户端（P4-T9）。
 *
 * <p>对接 NexusChain bridge 的跨链消息 API，支持发送、查询、列出与重试跨链消息。</p>
 *
 * <h3>对接端点</h3>
 * <ul>
 *   <li>{@code POST /api/v2/bridge/messages} — 发送跨链消息</li>
 *   <li>{@code GET  /api/v2/bridge/messages/{id}} — 查询消息详情/状态</li>
 *   <li>{@code GET  /api/v2/bridge/messages} — 列出消息（游标分页）</li>
 *   <li>{@code POST /api/v2/bridge/messages/{id}/retry} — 重试失败的消息</li>
 * </ul>
 *
 * <h3>支持的 format</h3>
 * <ul>
 *   <li>{@code "RAW"} — 原始字节（base64 编码）</li>
 *   <li>{@code "PROTOBUF"} — Protocol Buffers 编码</li>
 *   <li>{@code "JSON"} — JSON 字符串</li>
 * </ul>
 *
 * <pre>{@code
 * CrossChainMessageClient client = new CrossChainMessageClient("http://localhost:8080", "apiKey");
 * JsonNode sent = client.sendMessage("ETH", "BSC", "0xRecipient", "hello", "RAW");
 * String messageId = sent.get("messageId").asText();
 * JsonNode status = client.getMessageStatus(messageId);
 * CursorPage<JsonNode> page = client.listMessages("ETH", "BSC", null, 20);
 * }</pre>
 *
 * @see org.nexus.sdk.v2.NexusChainV2Client#crossChain()
 */
public class CrossChainMessageClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** API 版本头名 */
    public static final String VERSION_HEADER = "X-NexusChain-API-Version";

    /** API Key 头名 */
    public static final String API_KEY_HEADER = "X-NexusChain-ApiKey";

    /** 支持的消息格式 */
    public static final String FORMAT_RAW = "RAW";
    public static final String FORMAT_PROTOBUF = "PROTOBUF";
    public static final String FORMAT_JSON = "JSON";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    /**
     * 构造跨链消息客户端。
     *
     * @param baseUrl 网关/桥接基础 URL
     * @param apiKey  商户 API Key（可 null）
     */
    public CrossChainMessageClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null);
    }

    /**
     * 构造跨链消息客户端（复用外部 HttpClient）。
     */
    public CrossChainMessageClient(String baseUrl, String apiKey, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // === 公共 API ===

    /**
     * 发送跨链消息。
     *
     * @param sourceChain 源链标识（如 "ETH"、"BSC"、"SOLANA"）
     * @param targetChain 目标链标识
     * @param recipient   接收方地址（目标链格式）
     * @param payload     消息内容（按 format 解释）
     * @param format      消息格式（"RAW"/"PROTOBUF"/"JSON"）
     * @return 发送结果 JSON（含 messageId、status 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode sendMessage(String sourceChain, String targetChain,
                                String recipient, String payload, String format) {
        Objects.requireNonNull(sourceChain, "sourceChain");
        Objects.requireNonNull(targetChain, "targetChain");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(format, "format");
        validateFormat(format);
        Map<String, Object> body = new HashMap<>();
        body.put("sourceChain", sourceChain);
        body.put("targetChain", targetChain);
        body.put("recipient", recipient);
        body.put("payload", payload);
        body.put("format", format);
        HttpResponse<String> resp = post("/api/v2/bridge/messages", toJson(body));
        return parseJson(resp.body());
    }

    /**
     * 查询消息状态。
     *
     * @param messageId 消息 ID
     * @return 消息状态 JSON（含 status、confirmations 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode getMessageStatus(String messageId) {
        Objects.requireNonNull(messageId, "messageId");
        if (messageId.isEmpty()) {
            throw new V2ApiException("messageId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        HttpResponse<String> resp = get("/api/v2/bridge/messages/" + messageId + "/status");
        return parseJson(resp.body());
    }

    /**
     * 查询消息详情。
     *
     * @param messageId 消息 ID
     * @return 消息详情 JSON（含 sourceChain、targetChain、payload、status 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode getMessageDetails(String messageId) {
        Objects.requireNonNull(messageId, "messageId");
        if (messageId.isEmpty()) {
            throw new V2ApiException("messageId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        HttpResponse<String> resp = get("/api/v2/bridge/messages/" + messageId);
        return parseJson(resp.body());
    }

    /**
     * 列出消息（游标分页）。
     *
     * @param sourceChain 源链过滤（null 不过滤）
     * @param targetChain 目标链过滤（null 不过滤）
     * @param cursor      游标（首页传 null）
     * @param pageSize    每页条数（&lt;=0 取服务端默认 20）
     * @return 游标分页结果
     * @throws V2ApiException 当服务端返回错误时
     */
    public CursorPage<JsonNode> listMessages(String sourceChain, String targetChain,
                                             String cursor, int pageSize) {
        String query = buildQuery(
                "sourceChain", sourceChain,
                "targetChain", targetChain,
                "cursor", cursor,
                "pageSize", pageSize > 0 ? String.valueOf(pageSize) : null
        );
        HttpResponse<String> resp = get("/api/v2/bridge/messages" + query);
        return parseCursorPage(resp.body());
    }

    /**
     * 重试失败的消息。
     *
     * @param messageId 消息 ID
     * @return 重试结果 JSON（含 newMessageId、status 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode retryMessage(String messageId) {
        Objects.requireNonNull(messageId, "messageId");
        if (messageId.isEmpty()) {
            throw new V2ApiException("messageId must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        HttpResponse<String> resp = post("/api/v2/bridge/messages/" + messageId + "/retry", "{}");
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

    // === 内部工具 ===

    private void validateFormat(String format) {
        if (!FORMAT_RAW.equals(format) && !FORMAT_PROTOBUF.equals(format) && !FORMAT_JSON.equals(format)) {
            throw new V2ApiException(
                    "format must be one of RAW/PROTOBUF/JSON, got: " + format,
                    0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
    }

    private HttpResponse<String> get(String path) {
        return send("GET", path, null);
    }

    private HttpResponse<String> post(String path, String body) {
        return send("POST", path, body);
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