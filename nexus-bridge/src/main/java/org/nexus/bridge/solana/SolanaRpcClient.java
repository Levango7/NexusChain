package org.nexus.bridge.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Solana JSON RPC 客户端。
 *
 * <p>通过 HTTP POST 调用 Solana 节点的 JSON RPC endpoint，
 * 支持主网（mainnet-beta）、测试网（testnet）、开发网（devnet）以及自定义集群。</p>
 *
 * <h2>实现的 RPC 方法</h2>
 * <ul>
 *   <li>{@code getBalance} — 查询账户 SOL 余额（lamports）</li>
 *   <li>{@code getLatestBlockhash} — 获取最新区块哈希与最后有效区块高度</li>
 *   <li>{@code sendTransaction} — 提交已签名交易（base64 编码）</li>
 *   <li>{@code simulateTransaction} — 模拟交易执行，返回 logs / units consumed / err</li>
 *   <li>{@code getTransaction} — 按交易签名查询交易详情</li>
 *   <li>{@code getSlot} / {@code getBlockHeight} — 获取当前 slot / 区块高度</li>
 * </ul>
 *
 * <h2>请求格式</h2>
 * <pre>
 *   POST / HTTP/1.1
 *   Content-Type: application/json
 *
 *   {"jsonrpc":"2.0","id":1,"method":"getBalance","params":["..."]}
 * </pre>
 *
 * <h2>错误处理</h2>
 * <p>所有 IO / 解析异常统一捕获并记录日志，返回 {@code null} 或安全默认值；
 * 调用方可通过返回值判断调用是否成功。{@link #sendTransaction(String)} 在 RPC 失败时
 * 抛 {@link SolanaRpcException}，因为交易发送失败属于不可恢复的严重错误。</p>
 *
 * @since 2.0.0
 */
public class SolanaRpcClient {

    private static final Logger log = LoggerFactory.getLogger(SolanaRpcClient.class);

    /** Solana JSON RPC 协议版本。 */
    private static final String JSON_RPC_VERSION = "2.0";

    /** 默认 HTTP 超时（10 秒）。 */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** Base58 字母表（用于公钥格式校验）。 */
    private static final String BASE58_ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    /** 主网 endpoint。 */
    public static final String MAINNET_ENDPOINT = "https://api.mainnet-beta.solana.com";

    /** 测试网 endpoint。 */
    public static final String TESTNET_ENDPOINT = "https://api.testnet.solana.com";

    /** 开发网 endpoint。 */
    public static final String DEVNET_ENDPOINT = "https://api.devnet.solana.com";

    /** JSON 序列化/反序列化器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** HTTP 客户端（JDK 11+ 内置）。 */
    private final HttpClient httpClient;

    /** RPC endpoint URL。 */
    private final String rpcEndpoint;

    /** 自增请求 ID。 */
    private final AtomicLong requestId = new AtomicLong(0);

    /**
     * 构造 Solana RPC 客户端。
     *
     * @param rpcEndpoint RPC endpoint URL（mainnet / testnet / devnet / 自定义）
     */
    public SolanaRpcClient(String rpcEndpoint) {
        this(rpcEndpoint, HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .build());
    }

    /**
     * 构造 Solana RPC 客户端（注入自定义 HttpClient，便于测试）。
     *
     * @param rpcEndpoint RPC endpoint URL
     * @param httpClient  HTTP 客户端实例
     */
    public SolanaRpcClient(String rpcEndpoint, HttpClient httpClient) {
        if (rpcEndpoint == null || rpcEndpoint.isEmpty()) {
            throw new IllegalArgumentException("Solana RPC endpoint must not be null or empty");
        }
        this.rpcEndpoint = rpcEndpoint;
        this.httpClient = httpClient;
        log.info("Initialized SolanaRpcClient: endpoint={}", rpcEndpoint);
    }

    /**
     * 获取 RPC endpoint URL。
     *
     * @return endpoint URL
     */
    public String getRpcEndpoint() {
        return rpcEndpoint;
    }

    // ==================== RPC 方法 ====================

    /**
     * 查询账户 SOL 余额（单位：lamports，1 SOL = 10^9 lamports）。
     *
     * @param pubkey 账户公钥（base58 编码，32 字节）
     * @return 余额（lamports）；失败返回 -1
     */
    public long getBalance(String pubkey) {
        if (!isValidPubkey(pubkey)) {
            log.warn("Invalid pubkey for getBalance: {}", pubkey);
            return -1L;
        }
        try {
            JsonNode result = invoke("getBalance", arrayNode().add(pubkey));
            if (result == null || !result.has("value")) {
                return -1L;
            }
            return result.get("value").asLong(-1L);
        } catch (RuntimeException | IOException | InterruptedException e) {
            log.error("getBalance failed for {}: {}", pubkey, e.getMessage());
            return -1L;
        }
    }

    /**
     * 获取最新区块哈希（用于交易 recentBlockhash 字段）。
     *
     * @return {@link Blockhash} 对象；失败返回 null
     */
    public Blockhash getLatestBlockhash() {
        try {
            JsonNode result = invoke("getLatestBlockhash", objectMapper.createArrayNode());
            if (result == null || !result.has("value")) {
                return null;
            }
            JsonNode value = result.get("value");
            String blockhash = value.path("blockhash").asText(null);
            long lastValidBlockHeight = value.path("lastValidBlockHeight").asLong(-1L);
            return new Blockhash(blockhash, lastValidBlockHeight);
        } catch (RuntimeException | IOException | InterruptedException e) {
            log.error("getLatestBlockhash failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 提交已签名交易。
     *
     * @param signedTransactionBase64 已签名交易的 base64 编码字符串
     * @return 交易签名（base58 编码，64 字节）
     * @throws SolanaRpcException 如果 RPC 调用失败
     */
    public String sendTransaction(String signedTransactionBase64) throws SolanaRpcException {
        if (signedTransactionBase64 == null || signedTransactionBase64.isEmpty()) {
            throw new IllegalArgumentException("Signed transaction must not be null or empty");
        }
        // params: [transaction, {encoding: "base64"}]
        ArrayNode params = arrayNode()
                .add(signedTransactionBase64)
                .add(objectMapper.createObjectNode().put("encoding", "base64"));
        try {
            JsonNode result = invoke("sendTransaction", params);
            if (result == null || !result.isTextual()) {
                throw new SolanaRpcException("SEND_TX_FAILED",
                        "sendTransaction returned non-text result: " + result);
            }
            String signature = result.asText();
            log.debug("Submitted tx on Solana: signature={}", signature);
            return signature;
        } catch (SolanaRpcException e) {
            throw e;
        } catch (RuntimeException | IOException | InterruptedException e) {
            log.error("sendTransaction failed: {}", e.getMessage());
            throw new SolanaRpcException("SEND_TX_IO_ERROR",
                    "IO error sending transaction: " + e.getMessage(), e);
        }
    }

    /**
     * 模拟交易执行（不实际提交上链）。
     *
     * @param signedTransactionBase64 已签名交易的 base64 编码字符串
     * @return {@link SimulationResult}；失败返回包含 err 的结果
     */
    public SimulationResult simulateTransaction(String signedTransactionBase64) {
        if (signedTransactionBase64 == null || signedTransactionBase64.isEmpty()) {
            SimulationResult fail = new SimulationResult();
            fail.err = "Empty transaction";
            return fail;
        }
        // params: [transaction, {encoding: "base64", sigVerify: true}]
        ObjectNode config = objectMapper.createObjectNode()
                .put("encoding", "base64")
                .put("sigVerify", true);
        ArrayNode params = arrayNode()
                .add(signedTransactionBase64)
                .add(config);
        try {
            JsonNode result = invoke("simulateTransaction", params);
            if (result == null || !result.has("value")) {
                SimulationResult fail = new SimulationResult();
                fail.err = "Missing value in response";
                return fail;
            }
            return parseSimulationResult(result.get("value"));
        } catch (RuntimeException | IOException | InterruptedException e) {
            log.error("simulateTransaction failed: {}", e.getMessage());
            SimulationResult fail = new SimulationResult();
            fail.err = e.getMessage();
            return fail;
        }
    }

    /**
     * 按交易签名查询交易详情。
     *
     * @param signature 交易签名（base58 编码，64 字节）
     * @return 交易详情 JSON 节点；不存在或失败返回 null
     */
    public JsonNode getTransaction(String signature) {
        if (signature == null || signature.isEmpty()) {
            log.warn("Empty signature supplied to getTransaction");
            return null;
        }
        // params: [signature, {maxSupportedTransactionVersion: 0}]
        ObjectNode config = objectMapper.createObjectNode()
                .put("maxSupportedTransactionVersion", 0);
        ArrayNode params = arrayNode().add(signature).add(config);
        try {
            JsonNode result = invoke("getTransaction", params);
            if (result == null || result.isNull()) {
                return null;
            }
            // result 直接是交易对象（可为 null 表示未确认）
            return result;
        } catch (RuntimeException | IOException | InterruptedException e) {
            log.error("getTransaction failed for {}: {}", signature, e.getMessage());
            return null;
        }
    }

    /**
     * 获取当前 slot（Solana 的区块高度单位）。
     *
     * @return 当前 slot；失败返回 -1
     */
    public long getSlot() {
        try {
            JsonNode result = invoke("getSlot", objectMapper.createArrayNode());
            if (result == null || !result.isNumber()) {
                return -1L;
            }
            return result.asLong(-1L);
        } catch (RuntimeException | IOException | InterruptedException e) {
            log.error("getSlot failed: {}", e.getMessage());
            return -1L;
        }
    }

    /**
     * 获取当前区块高度（已确认的最深区块高度）。
     *
     * @return 区块高度；失败返回 -1
     */
    public long getBlockHeight() {
        try {
            JsonNode result = invoke("getBlockHeight", objectMapper.createArrayNode());
            if (result == null || !result.isNumber()) {
                return -1L;
            }
            return result.asLong(-1L);
        } catch (RuntimeException | IOException | InterruptedException e) {
            log.error("getBlockHeight failed: {}", e.getMessage());
            return -1L;
        }
    }

    /**
     * 查询指定 owner 的所有 SPL Token 账户。
     *
     * <p>通常以 {@code programId = TokenkegQfeZyiNwAJbNbGKPFXCWuBhf924s93HX2TN}
     * 过滤，配合 {@code encoding = jsonParsed} 直接拿到 amount / decimals。</p>
     *
     * @param params 完整 params 节点（[owner, filter, config]）
     * @return RPC result 节点；失败返回 null
     */
    public JsonNode getTokenAccountsByOwner(JsonNode params) {
        try {
            return invoke("getTokenAccountsByOwner", params);
        } catch (RuntimeException | IOException | InterruptedException e) {
            log.error("getTokenAccountsByOwner failed: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 内部工具 ====================

    /**
     * 调用指定 RPC 方法并返回 result 字段。
     *
     * @param method RPC 方法名
     * @param params 参数节点
     * @return result 字段；RPC 错误时返回 null
     * @throws IOException          网络 IO 错误
     * @throws SolanaRpcException   RPC 返回 error 对象
     * @throws InterruptedException HTTP 请求被中断
     */
    private JsonNode invoke(String method, JsonNode params)
            throws IOException, SolanaRpcException, InterruptedException {
        long id = requestId.incrementAndGet();
        ObjectNode request = objectMapper.createObjectNode()
                .put("jsonrpc", JSON_RPC_VERSION)
                .put("id", id)
                .put("method", method);
        request.set("params", params);

        String requestBody = objectMapper.writeValueAsString(request);
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(rpcEndpoint))
                .timeout(DEFAULT_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(
                httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new SolanaRpcException("HTTP_ERROR",
                    "HTTP " + response.statusCode() + ": " + response.body());
        }
        JsonNode responseNode = objectMapper.readTree(response.body());
        if (responseNode.has("error")) {
            JsonNode err = responseNode.get("error");
            int code = err.path("code").asInt(-1);
            String message = err.path("message").asText("Unknown RPC error");
            throw new SolanaRpcException("RPC_ERROR_" + code, message);
        }
        return responseNode.get("result");
    }

    /**
     * 创建一个新的 ArrayNode（简化调用）。
     *
     * @return ArrayNode
     */
    private ArrayNode arrayNode() {
        return objectMapper.createArrayNode();
    }

    /**
     * 解析 simulateTransaction 返回的 value 节点。
     *
     * @param value value 节点
     * @return {@link SimulationResult}
     */
    private SimulationResult parseSimulationResult(JsonNode value) {
        SimulationResult sim = new SimulationResult();
        if (value.has("err") && !value.get("err").isNull()) {
            JsonNode errNode = value.get("err");
            // err 可能是字符串、对象或数组；统一转为字符串表示
            sim.err = errNode.isTextual() ? errNode.asText() : errNode.toString();
        } else {
            sim.err = null;
        }
        sim.logs = new java.util.ArrayList<>();
        if (value.has("logs") && value.get("logs").isArray()) {
            for (JsonNode logEntry : value.get("logs")) {
                sim.logs.add(logEntry.asText());
            }
        }
        sim.unitsConsumed = value.path("unitsConsumed").asLong(0);
        if (value.has("accounts") && value.get("accounts").isArray()) {
            sim.accounts = new java.util.ArrayList<>();
            for (JsonNode acc : value.get("accounts")) {
                sim.accounts.add(acc.path("pubkey").asText());
            }
        }
        return sim;
    }

    /**
     * 简单校验公钥格式（base58 字符集，长度 32-44 之间）。
     *
     * @param pubkey 待校验公钥
     * @return 合法返回 true
     */
    private static boolean isValidPubkey(String pubkey) {
        if (pubkey == null || pubkey.isEmpty() || pubkey.length() > 44) {
            return false;
        }
        for (int i = 0; i < pubkey.length(); i++) {
            if (BASE58_ALPHABET.indexOf(pubkey.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    // ==================== 内嵌类型 ====================

    /**
     * 区块哈希信息。
     */
    public static final class Blockhash {
        /** 区块哈希（base58 编码，32 字节）。 */
        public final String blockhash;
        /** 该 blockhash 最后有效的区块高度。 */
        public final long lastValidBlockHeight;

        /**
         * 构造 Blockhash。
         *
         * @param blockhash            区块哈希
         * @param lastValidBlockHeight 最后有效区块高度
         */
        public Blockhash(String blockhash, long lastValidBlockHeight) {
            this.blockhash = blockhash;
            this.lastValidBlockHeight = lastValidBlockHeight;
        }

        @Override
        public String toString() {
            return "Blockhash{blockhash='" + blockhash + '\''
                    + ", lastValidBlockHeight=" + lastValidBlockHeight + '}';
        }
    }

    /**
     * 交易模拟结果。
     */
    public static final class SimulationResult {
        /** 错误信息（成功时为 null）。 */
        public String err;
        /** 程序日志行。 */
        public java.util.List<String> logs;
        /** 消耗的 Compute Units。 */
        public long unitsConsumed;
        /** 涉及的账户公钥列表。 */
        public java.util.List<String> accounts;

        /**
         * 判断模拟是否成功。
         *
         * @return 成功返回 true
         */
        public boolean success() {
            return err == null;
        }

        @Override
        public String toString() {
            return "SimulationResult{err='" + err + '\''
                    + ", unitsConsumed=" + unitsConsumed
                    + ", logs=" + (logs != null ? logs.size() : 0) + " lines}";
        }
    }
}