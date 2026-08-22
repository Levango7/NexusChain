package org.nexus.bridge.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nexus.bridge.solana.SolanaRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Solana 链适配器，实现 {@link ChainAdapter} 接口。
 *
 * <p>通过 {@link SolanaRpcClient} 与 Solana 节点交互，
 * 适配 Solana 的账户模型（base58 编码）与 SPL Token 标准。</p>
 *
 * <h2>与 EVM 适配器的差异</h2>
 * <ul>
 *   <li>账户地址使用 base58 编码（32 字节公钥），非 hex</li>
 *   <li>代币余额通过 SPL Token Program 查询，非 ERC20</li>
 *   <li>「合约调用」对应 Solana Program 的 simulateTransaction</li>
 *   <li>「交易回执」对应 getTransaction 返回的元数据</li>
 * </ul>
 *
 * <h2>配置项</h2>
 * <ul>
 *   <li>{@code nexus.bridge.solana.rpc-endpoint} — Solana RPC 端点 URL</li>
 *   <li>{@code nexus.bridge.solana.chain-id} — 链 ID（默认 {@code solana-mainnet}）</li>
 *   <li>{@code nexus.bridge.solana.spl-token-program} — SPL Token Program ID
 *       （默认 {@code TokenkegQfeZyiNwAJbNbGKPFXCWuBhf924s93HX2TN}，即官方 Token Program）</li>
 * </ul>
 *
 * <h2>SPL Token 余额查询</h2>
 * <p>Solana 的代币余额存储在「代币账户」（Token Account）中，每个 Token Account 绑定
 * 一个 owner 和一个 mint。查询某地址的 SPL Token 余额需要：
 * <ol>
 *   <li>调用 {@code getTokenAccountsByOwner} 获取该 owner 的所有 Token Account</li>
 *   <li>对每个 Token Account 调用 {@code getParsedAccountInfo} 解析 amount 字段</li>
 * </ol>
 * 本适配器在 {@link #getAddressBalance(String, String)} 中实现该流程。</p>
 *
 * @since 2.0.0
 */
@Component
public class SolanaAdapter implements ChainAdapter {

    private static final Logger log = LoggerFactory.getLogger(SolanaAdapter.class);

    /** Solana 主网链 ID 标识。 */
    public static final String CHAIN_ID_MAINNET = "solana-mainnet";

    /** 官方 SPL Token Program ID（Token Program，非 Token-2022）。 */
    public static final String DEFAULT_SPL_TOKEN_PROGRAM = "TokenkegQfeZyiNwAJbNbGKPFXCWuBhf924s93HX2TN";

    /** 链 ID。 */
    private final String chainId;

    /** SPL Token Program ID。 */
    private final String splTokenProgramId;

    /** Solana RPC 客户端。 */
    private final SolanaRpcClient rpcClient;

    /** JSON 序列化器（用于构造 getTokenAccountsByOwner 的 config 节点）。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Spring 注入构造函数。
     *
     * @param rpcEndpoint      RPC 端点 URL，取自配置 {@code nexus.bridge.solana.rpc-endpoint}
     * @param chainId          链 ID，取自配置 {@code nexus.bridge.solana.chain-id}
     * @param splTokenProgramId SPL Token Program ID，取自配置 {@code nexus.bridge.solana.spl-token-program}
     */
    @Autowired
    public SolanaAdapter(
            @Value("${nexus.bridge.solana.rpc-endpoint:https://api.mainnet-beta.solana.com}") String rpcEndpoint,
            @Value("${nexus.bridge.solana.chain-id:" + CHAIN_ID_MAINNET + "}") String chainId,
            @Value("${nexus.bridge.solana.spl-token-program:" + DEFAULT_SPL_TOKEN_PROGRAM + "}") String splTokenProgramId) {
        this(chainId, splTokenProgramId, new SolanaRpcClient(rpcEndpoint));
    }

    /**
     * 测试用构造函数（注入 RPC 客户端，非 Spring 注入）。
     *
     * @param chainId          链 ID
     * @param splTokenProgramId SPL Token Program ID
     * @param rpcClient        Solana RPC 客户端
     */
    public SolanaAdapter(String chainId, String splTokenProgramId, SolanaRpcClient rpcClient) {
        this.chainId = chainId;
        this.splTokenProgramId = splTokenProgramId;
        this.rpcClient = rpcClient;
        log.info("Initialized SolanaAdapter: chainId={}, splTokenProgram={}, endpoint={}",
                chainId, splTokenProgramId, rpcClient.getRpcEndpoint());
    }

    @Override
    public String getChainId() {
        return chainId;
    }

    @Override
    public long getBlockHeight() {
        return rpcClient.getBlockHeight();
    }

    @Override
    public String sendTransaction(byte[] tx) {
        if (tx == null || tx.length == 0) {
            throw new IllegalArgumentException("Transaction bytes must not be null or empty");
        }
        // Solana 交易字节以 base64 编码提交
        String base64Tx = Base64.getEncoder().encodeToString(tx);
        return rpcClient.sendTransaction(base64Tx);
    }

    @Override
    public Object getTransactionReceipt(String hash) {
        if (hash == null || hash.isEmpty()) {
            log.warn("Empty transaction hash supplied to getTransactionReceipt on chain {}", chainId);
            return null;
        }
        return rpcClient.getTransaction(hash);
    }

    @Override
    public String callContract(String address, String data) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("Program address must not be null or empty");
        }
        // Solana 的「合约调用」对应 simulateTransaction
        // data 视为已签名的 base64 交易；若 data 不带 base64 前缀，则尝试 UTF-8 → base64
        String base64Tx;
        if (data == null || data.isEmpty()) {
            return null;
        }
        try {
            // 校验是否为合法 base64
            Base64.getDecoder().decode(data);
            base64Tx = data;
        } catch (IllegalArgumentException e) {
            // 非 base64，按 UTF-8 编码后转 base64（用于测试 / 简单调用）
            base64Tx = Base64.getEncoder().encodeToString(data.getBytes(StandardCharsets.UTF_8));
        }
        SolanaRpcClient.SimulationResult result = rpcClient.simulateTransaction(base64Tx);
        if (result == null) {
            return null;
        }
        // 返回 JSON 字符串：{err, unitsConsumed, logs}
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("err", result.err);
            node.put("unitsConsumed", result.unitsConsumed);
            if (result.logs != null) {
                ArrayNode logsNode = node.putArray("logs");
                for (String logEntry : result.logs) {
                    logsNode.add(logEntry);
                }
            }
            return objectMapper.writeValueAsString(node);
        } catch (RuntimeException | IOException e) {
            log.error("Failed to serialize simulation result: {}", e.getMessage());
            return null;
        }
    }

    // ==================== Solana 专属方法 ====================

    /**
     * 查询账户 SOL 余额（原生代币，单位：lamports）。
     *
     * @param pubkey 账户公钥（base58）
     * @return 余额（lamports）；失败返回 -1
     */
    public long getSolBalance(String pubkey) {
        return rpcClient.getBalance(pubkey);
    }

    /**
     * 查询账户 SPL Token 余额。
     *
     * <p>通过 {@code getTokenAccountsByOwner} 获取该 owner 在指定 mint 下的
     * 所有 Token Account，累加解析后的 amount 字段。</p>
     *
     * @param ownerPubkey 账户公钥（base58）
     * @param mintPubkey  代币 mint 公钥（base58）
     * @return 余额（最小单位，1 token = 10^decimals 最小单位）；失败返回 -1
     */
    public long getAddressBalance(String ownerPubkey, String mintPubkey) {
        if (ownerPubkey == null || ownerPubkey.isEmpty()
                || mintPubkey == null || mintPubkey.isEmpty()) {
            log.warn("Invalid owner or mint pubkey: owner={}, mint={}", ownerPubkey, mintPubkey);
            return -1L;
        }
        try {
            // 构造 getTokenAccountsByOwner 的 params
            // params: [owner, {programId: splTokenProgramId}, {encoding: "jsonParsed"}]
            ObjectNode filter = objectMapper.createObjectNode()
                    .put("programId", splTokenProgramId);
            ObjectNode config = objectMapper.createObjectNode()
                    .put("encoding", "jsonParsed");
            ArrayNode params = objectMapper.createArrayNode()
                    .add(ownerPubkey)
                    .add(filter)
                    .add(config);

            JsonNode result = rpcClient.getTokenAccountsByOwner(params);
            if (result == null || !result.has("value") || !result.get("value").isArray()) {
                return -1L;
            }
            long total = 0L;
            for (JsonNode accountNode : result.get("value")) {
                long amount = parseTokenAmount(accountNode, mintPubkey);
                if (amount > 0) {
                    total += amount;
                }
            }
            return total;
        } catch (RuntimeException e) {
            log.error("getAddressBalance failed for owner={}, mint={}: {}",
                    ownerPubkey, mintPubkey, e.getMessage());
            return -1L;
        }
    }

    /**
     * 获取最新区块哈希（用于构造交易的 recentBlockhash 字段）。
     *
     * @return {@link SolanaRpcClient.Blockhash}；失败返回 null
     */
    public SolanaRpcClient.Blockhash getLatestBlockhash() {
        return rpcClient.getLatestBlockhash();
    }

    /**
     * 模拟交易执行（不实际提交上链）。
     *
     * @param signedTransactionBase64 已签名交易的 base64 编码
     * @return {@link SolanaRpcClient.SimulationResult}
     */
    public SolanaRpcClient.SimulationResult simulateTransaction(String signedTransactionBase64) {
        return rpcClient.simulateTransaction(signedTransactionBase64);
    }

    /**
     * 获取 SPL Token Program ID。
     *
     * @return Program ID
     */
    public String getSplTokenProgramId() {
        return splTokenProgramId;
    }

    /**
     * 获取底层 RPC 客户端（仅供测试与高级用途使用）。
     *
     * @return SolanaRpcClient 实例
     */
    public SolanaRpcClient getRpcClient() {
        return rpcClient;
    }

    /**
     * 关闭底层 HTTP 客户端，释放资源。
     */
    public void shutdown() {
        // HttpClient 不需要显式关闭，此处仅记录日志
        log.info("Shutdown SolanaAdapter for chain {}", chainId);
    }

    // ==================== 内部工具 ====================

    /**
     * 从 Token Account 节点解析 amount 字段。
     *
     * <p>jsonParsed 编码下，账户数据形如：
     * <pre>
     *   {
     *     "account": {
     *       "data": {
     *         "parsed": {
     *           "info": {
     *             "mint": "...",
     *             "tokenAmount": {"amount": "1000000", "decimals": 6}
     *           }
     *         }
     *       }
     *     }
     *   }
     * </pre>
     * 仅当 mint 匹配时累加 amount。</p>
     *
     * @param accountNode Token Account 节点
     * @param mintPubkey  期望的 mint 公钥
     * @return amount（最小单位）；不匹配或解析失败返回 0
     */
    private long parseTokenAmount(JsonNode accountNode, String mintPubkey) {
        try {
            JsonNode info = accountNode
                    .path("account")
                    .path("data")
                    .path("parsed")
                    .path("info");
            String mint = info.path("mint").asText("");
            if (!mintPubkey.equals(mint)) {
                return 0L;
            }
            String amountStr = info.path("tokenAmount").path("amount").asText("0");
            return Long.parseLong(amountStr);
        } catch (RuntimeException e) {
            log.debug("Failed to parse token amount: {}", e.getMessage());
            return 0L;
        }
    }
}