package org.nexus.sdk.bridge;

import org.nexus.sdk.RpcClient;
import org.nexus.sdk.TransactionBuilder;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * 跨链桥客户端。
 *
 * <p>提供 NexusChain 与外部链（Ethereum、BSC、Polygon 等）之间的
 * 资产跨链锁定、解锁和状态跟踪能力。</p>
 *
 * <p>写操作（{@link #lock} / {@link #unlock}）通过 {@link TransactionBuilder}
 * 构造 BRIDGE 交易并经 {@link RpcClient} 广播；读操作经 RPC 查询链上状态。
 * 签名由钱包层负责（本客户端仅构造与广播）。</p>
 */
public class BridgeClient {

    /** 桥合约默认地址（可由服务端注册后通过配置覆盖）。 */
    private static final String DEFAULT_BRIDGE_CONTRACT = "0x000000000000000000000000000000000000b01d";

    /** 支持的跨链目标链。 */
    private static final List<String> SUPPORTED_CHAINS =
            Arrays.asList("ethereum", "bsc", "polygon");

    /** 基础跨链手续费（NEX 最小单位）。 */
    private static final BigInteger BASE_BRIDGE_FEE = BigInteger.valueOf(1_000_000L);

    private final RpcClient rpcClient;
    private final TransactionBuilder transactionBuilder;
    private final String bridgeContract;

    public BridgeClient(RpcClient rpcClient) {
        this(rpcClient, DEFAULT_BRIDGE_CONTRACT);
    }

    public BridgeClient(RpcClient rpcClient, String bridgeContract) {
        this(rpcClient, bridgeContract, new TransactionBuilder(rpcClient, "mainnet"));
    }

    public BridgeClient(RpcClient rpcClient, String bridgeContract, TransactionBuilder transactionBuilder) {
        if (rpcClient == null) {
            throw new IllegalArgumentException("rpcClient is required");
        }
        this.rpcClient = rpcClient;
        this.bridgeContract = bridgeContract != null ? bridgeContract : DEFAULT_BRIDGE_CONTRACT;
        this.transactionBuilder = transactionBuilder;
    }

    /**
     * 锁定资产，发起跨链转移。
     *
     * @param from         发送方地址（NexusChain 侧）
     * @param token        代币符号或合约地址
     * @param amount       跨链数量（最小单位）
     * @param targetChain  目标链标识（如 "ethereum"、"bsc"、"polygon"）
     * @param targetAddress 目标链上的接收地址
     * @return 跨链交易哈希
     */
    public String lock(String from, String token, BigInteger amount,
                       String targetChain, String targetAddress) {
        requireNonEmpty(from, "from");
        requireNonEmpty(token, "token");
        requirePositive(amount, "amount");
        requireNonEmpty(targetChain, "targetChain");
        requireNonEmpty(targetAddress, "targetAddress");
        if (!SUPPORTED_CHAINS.contains(targetChain.toLowerCase())) {
            throw new IllegalArgumentException("unsupported target chain: " + targetChain);
        }

        // 构造 BRIDGE_LOCK 调用数据并经 RPC 广播
        String callData = encodeBridgeCall("lock", token, amount, targetChain, targetAddress);
        return invokeBridge("lock", from, callData);
    }

    /**
     * 解锁资产，完成跨链接收。
     *
     * @param to           接收方地址（NexusChain 侧）
     * @param token        代币符号或合约地址
     * @param amount       解锁数量（最小单位）
     * @param sourceChain  源链标识
     * @param proof        跨链证明数据
     * @return 解锁交易哈希
     */
    public String unlock(String to, String token, BigInteger amount,
                         String sourceChain, String proof) {
        requireNonEmpty(to, "to");
        requireNonEmpty(token, "token");
        requirePositive(amount, "amount");
        requireNonEmpty(sourceChain, "sourceChain");
        requireNonEmpty(proof, "proof");

        String callData = encodeBridgeCall("unlock", token, amount, sourceChain, proof);
        return invokeBridge("unlock", to, callData);
    }

    /**
     * 查询跨链交易状态。
     *
     * @param txHash 源链上的交易哈希
     * @return 跨链交易状态信息
     */
    public BridgeStatus getBridgeStatus(String txHash) {
        requireNonEmpty(txHash, "txHash");
        Object result = rpcClient.call("nexus_getTransactionByHash", new Object[]{txHash});
        if (result == null) {
            return new BridgeStatus(txHash, "pending", null, null, 0L);
        }
        if (result instanceof com.fasterxml.jackson.databind.JsonNode) {
            com.fasterxml.jackson.databind.JsonNode node = (com.fasterxml.jackson.databind.JsonNode) result;
            String status = node.has("status") ? node.get("status").asText() : "pending";
            long confirmations = node.has("confirmations") ? node.get("confirmations").asLong() : 0L;
            String source = node.has("from") ? node.get("from").asText() : null;
            String target = node.has("to") ? node.get("to").asText() : null;
            return new BridgeStatus(txHash, status, source, target, confirmations);
        }
        return new BridgeStatus(txHash, "pending", null, null, 0L);
    }

    /**
     * 查询支持的目标链列表。
     *
     * @return 目标链标识数组
     */
    public String[] getSupportedChains() {
        return SUPPORTED_CHAINS.toArray(new String[0]);
    }

    /**
     * 查询跨链手续费。
     *
     * @param token       代币符号
     * @param targetChain 目标链标识
     * @return 手续费（NEX，最小单位 wei）
     */
    public BigInteger getBridgeFee(String token, String targetChain) {
        requireNonEmpty(token, "token");
        requireNonEmpty(targetChain, "targetChain");
        if (!SUPPORTED_CHAINS.contains(targetChain.toLowerCase())) {
            throw new IllegalArgumentException("unsupported target chain: " + targetChain);
        }
        // 基础手续费；生产可改为查询桥合约的动态费率
        return BASE_BRIDGE_FEE;
    }

    /**
     * 经桥合约 RPC 端点发起调用并返回交易哈希。
     */
    private String invokeBridge(String method, String from, String callData) {
        Object result = rpcClient.call("nexus_callContract",
                new Object[]{bridgeContract, method, new Object[]{callData}, "wasm"});
        if (result instanceof com.fasterxml.jackson.databind.JsonNode) {
            com.fasterxml.jackson.databind.JsonNode node = (com.fasterxml.jackson.databind.JsonNode) result;
            if (node.has("returnValue")) {
                return node.get("returnValue").asText();
            }
        }
        return result != null ? result.toString() : null;
    }

    /**
     * 编码桥调用数据（method|token|amount|chain|addressOrProof）。
     */
    private String encodeBridgeCall(String method, String token, BigInteger amount,
                                    String chain, String addressOrProof) {
        return method + "|" + token + "|" + amount + "|" + chain + "|" + addressOrProof;
    }

    private void requireNonEmpty(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private void requirePositive(BigInteger value, String name) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /**
     * 跨链交易状态。
     */
    public static class BridgeStatus {
        private final String txHash;
        private final String status;       // pending | confirmed | completed | failed
        private final String sourceChain;
        private final String targetChain;
        private final long confirmations;

        public BridgeStatus(String txHash, String status, String sourceChain,
                            String targetChain, long confirmations) {
            this.txHash = txHash;
            this.status = status;
            this.sourceChain = sourceChain;
            this.targetChain = targetChain;
            this.confirmations = confirmations;
        }

        public String getTxHash() { return txHash; }
        public String getStatus() { return status; }
        public String getSourceChain() { return sourceChain; }
        public String getTargetChain() { return targetChain; }
        public long getConfirmations() { return confirmations; }
    }
}
