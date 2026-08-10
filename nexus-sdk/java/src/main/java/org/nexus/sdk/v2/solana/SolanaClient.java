package org.nexus.sdk.v2.solana;

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

/**
 * Solana 链操作客户端（P4-T9）。
 *
 * <p>对接 NexusChain bridge 的 Solana API，提供 Solana 链上的支付创建、
 * 交易状态查询、余额查询与手续费估算能力。</p>
 *
 * <h3>对接端点</h3>
 * <ul>
 *   <li>{@code POST /api/v2/bridge/solana/payment} — 创建 Solana 支付</li>
 *   <li>{@code GET  /api/v2/bridge/solana/tx/{signature}} — 查询交易状态</li>
 *   <li>{@code GET  /api/v2/bridge/solana/balance/{address}} — 查询余额</li>
 *   <li>{@code POST /api/v2/bridge/solana/estimate-fee} — 估算手续费</li>
 * </ul>
 *
 * <pre>{@code
 * SolanaClient client = new SolanaClient("http://localhost:8080", "apiKey");
 * JsonNode payment = client.createPayment(
 *     "FromPubKey...", "ToPubKey...", 1_000_000L, "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v");
 * String signature = payment.get("signature").asText();
 * JsonNode status = client.getTransactionStatus(signature);
 * }</pre>
 *
 * @see org.nexus.sdk.v2.NexusChainV2Client#solana()
 */
public class SolanaClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** API 版本头名 */
    public static final String VERSION_HEADER = "X-NexusChain-API-Version";

    /** API Key 头名 */
    public static final String API_KEY_HEADER = "X-NexusChain-ApiKey";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient httpClient;

    /**
     * 构造 Solana 客户端。
     *
     * @param baseUrl 网关/桥接基础 URL（如 "http://localhost:8080"）
     * @param apiKey  商户 API Key（可 null，用于公开端点）
     */
    public SolanaClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, null);
    }

    /**
     * 构造 Solana 客户端（复用外部 HttpClient）。
     *
     * @param baseUrl    网关/桥接基础 URL
     * @param apiKey     商户 API Key（可 null）
     * @param httpClient 外部 HttpClient（null 则内部新建）
     */
    public SolanaClient(String baseUrl, String apiKey, HttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.httpClient = httpClient != null ? httpClient : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // === 公共 API ===

    /**
     * 创建 Solana 支付。
     *
     * @param fromAddress   付款方钱包公钥（base58）
     * @param toAddress     收款方钱包公钥（base58）
     * @param amountLamports 金额（lamports，1 SOL = 1_000_000_000 lamports）
     * @param mint          SPL Token mint 地址（null 或 "native" 表示原生 SOL）
     * @return 支付创建结果 JSON（含 signature、slot 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode createPayment(String fromAddress, String toAddress,
                                  long amountLamports, String mint) {
        Objects.requireNonNull(fromAddress, "fromAddress");
        Objects.requireNonNull(toAddress, "toAddress");
        if (amountLamports < 0) {
            throw new V2ApiException("amountLamports must be >= 0", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("fromAddress", fromAddress);
        body.put("toAddress", toAddress);
        body.put("amountLamports", amountLamports);
        body.put("mint", mint == null ? "native" : mint);
        HttpResponse<String> resp = post("/api/v2/bridge/solana/payment", toJson(body));
        return parseJson(resp.body());
    }

    /**
     * 查询交易状态。
     *
     * @param signature 交易签名（base58）
     * @return 交易状态 JSON（含 status、slot、confirmations 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode getTransactionStatus(String signature) {
        Objects.requireNonNull(signature, "signature");
        if (signature.isEmpty()) {
            throw new V2ApiException("signature must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        HttpResponse<String> resp = get("/api/v2/bridge/solana/tx/" + signature);
        return parseJson(resp.body());
    }

    /**
     * 查询钱包余额。
     *
     * @param address 钱包公钥（base58）
     * @return 余额 JSON（含 lamports、SOL、tokens 等）
     * @throws V2ApiException 当参数非法或服务端返回错误时
     */
    public JsonNode getBalance(String address) {
        Objects.requireNonNull(address, "address");
        if (address.isEmpty()) {
            throw new V2ApiException("address must not be empty", 0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        HttpResponse<String> resp = get("/api/v2/bridge/solana/balance/" + address);
        return parseJson(resp.body());
    }

    /**
     * 估算手续费。
     *
     * @param priorityLevel 优先级等级（0=最低, 1=低, 2=中, 3=高）
     * @return 手续费估算 JSON（含 lamports、priorityFee 等）
     * @throws V2ApiException 当 priorityLevel 不在 [0,3] 范围时
     */
    public JsonNode estimateFee(long priorityLevel) {
        if (priorityLevel < 0 || priorityLevel > 3) {
            throw new V2ApiException(
                    "priorityLevel must be in [0, 3], got " + priorityLevel,
                    0, "INVALID_ARGUMENT", null, (Map<String, Object>) null);
        }
        Map<String, Object> body = new HashMap<>();
        body.put("priorityLevel", priorityLevel);
        HttpResponse<String> resp = post("/api/v2/bridge/solana/estimate-fee", toJson(body));
        return parseJson(resp.body());
    }

    // === 包内可见的 HTTP 工具（供测试验证请求构造） ===

    /**
     * 构造 GET 请求（包内可见，供测试验证）。
     *
     * @param path 路径
     * @return 构造好的 HttpRequest
     */
    HttpRequest buildGetRequest(String path) {
        return buildRequest("GET", path, null);
    }

    /**
     * 构造 POST 请求（包内可见，供测试验证）。
     *
     * @param path 路径
     * @param body 请求体
     * @return 构造好的 HttpRequest
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

    // === 内部 HTTP 工具 ===

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

    private String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            throw new V2ApiException("Failed to serialize request: " + e.getMessage(),
                    0, "SERIALIZE_ERROR", null, e);
        }
    }
}