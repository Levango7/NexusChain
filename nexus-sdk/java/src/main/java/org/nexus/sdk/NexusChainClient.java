package org.nexus.sdk;

import org.nexus.sdk.channel.PaymentChannelClient;
import org.nexus.sdk.stablecoin.StableCoinClient;
import org.nexus.sdk.bridge.BridgeClient;

/**
 * NexusChain SDK 主客户端。
 *
 * <p>统一入口，聚合钱包管理、交易构造/签名/广播、RPC 客户端、
 * 支付通道、稳定币和跨链等全部能力。</p>
 *
 * <p>代币符号：NEX</p>
 *
 * <pre>{@code
 * NexusChainClient client = new NexusChainClient.Builder()
 *     .network("mainnet")
 *     .rpcUrl("https://rpc.nexus.network")
 *     .build();
 * }</pre>
 */
public class NexusChainClient {

    private final String network;
    private final String rpcUrl;
    private final int timeoutMs;
    private final String apiKey;

    private final Wallet wallet;
    private final TransactionBuilder transactionBuilder;
    private final RpcClient rpcClient;
    private final PaymentChannelClient paymentChannelClient;
    private final StableCoinClient stableCoinClient;
    private final BridgeClient bridgeClient;

    private NexusChainClient(Builder builder) {
        this.network = builder.network;
        this.rpcUrl = builder.rpcUrl;
        this.timeoutMs = builder.timeoutMs;
        this.apiKey = builder.apiKey;

        this.rpcClient = new RpcClient(rpcUrl, timeoutMs, apiKey);
        this.wallet = new Wallet(rpcClient, network);
        this.transactionBuilder = new TransactionBuilder(rpcClient, network);
        this.paymentChannelClient = new PaymentChannelClient(rpcClient);
        this.stableCoinClient = new StableCoinClient(rpcClient);
        this.bridgeClient = new BridgeClient(rpcClient);
    }

    /**
     * 获取钱包管理接口。
     *
     * @return 钱包实例
     */
    public Wallet wallet() {
        return wallet;
    }

    /**
     * 获取交易构造器。
     *
     * @return 交易构造器实例
     */
    public TransactionBuilder transactionBuilder() {
        return transactionBuilder;
    }

    /**
     * 获取底层 RPC 客户端。
     *
     * @return RPC 客户端实例
     */
    public RpcClient rpcClient() {
        return rpcClient;
    }

    /**
     * 获取支付通道客户端。
     *
     * @return 支付通道客户端实例
     */
    public PaymentChannelClient paymentChannel() {
        return paymentChannelClient;
    }

    /**
     * 获取稳定币客户端。
     *
     * @return 稳定币客户端实例
     */
    public StableCoinClient stableCoin() {
        return stableCoinClient;
    }

    /**
     * 获取跨链客户端。
     *
     * @return 跨链客户端实例
     */
    public BridgeClient bridge() {
        return bridgeClient;
    }

    /**
     * 获取当前网络类型。
     *
     * @return 网络名称（mainnet / testnet）
     */
    public String getNetwork() {
        return network;
    }

    /**
     * 获取 RPC 地址。
     *
     * @return RPC URL
     */
    public String getRpcUrl() {
        return rpcUrl;
    }

    /**
     * NexusChainClient 构造器。
     */
    public static class Builder {
        private String network = "mainnet";
        private String rpcUrl = "https://rpc.nexus.network";
        private int timeoutMs = 30000;
        private String apiKey;

        /**
         * 设置网络类型。
         *
         * @param network 网络名称（mainnet / testnet）
         * @return 当前 Builder
         */
        public Builder network(String network) {
            this.network = network;
            return this;
        }

        /**
         * 设置 RPC 节点地址。
         *
         * @param rpcUrl RPC URL
         * @return 当前 Builder
         */
        public Builder rpcUrl(String rpcUrl) {
            this.rpcUrl = rpcUrl;
            return this;
        }

        /**
         * 设置请求超时时间。
         *
         * @param timeoutMs 超时毫秒数
         * @return 当前 Builder
         */
        public Builder timeout(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        /**
         * 设置 API 密钥（用于付费节点认证）。
         *
         * @param apiKey API 密钥
         * @return 当前 Builder
         */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * 构建 NexusChainClient 实例。
         *
         * @return NexusChainClient 实例
         */
        public NexusChainClient build() {
            return new NexusChainClient(this);
        }
    }
}
