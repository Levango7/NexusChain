package org.nexus.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Gateway-level configuration, bound from the {@code nexus.*} prefix in application.yml.
 *
 * <p>Holds chain RPC endpoint, exchange-wallet service URL, webhook callback settings,
 * checkout parameters, and subscription retry policy.</p>
 */
@Configuration
@ConfigurationProperties(prefix = "nexus")
public class GatewayConfig {

    /** Token symbol used across the NexusChain platform. */
    private String tokenSymbol = "NEX";

    private ChainConfig chain = new ChainConfig();
    private ConsortiumConfig consortium = new ConsortiumConfig();
    private ExchangeWalletConfig exchangeWallet = new ExchangeWalletConfig();
    private WebhookConfig webhook = new WebhookConfig();
    private CheckoutConfig checkout = new CheckoutConfig();
    private SubscriptionConfig subscription = new SubscriptionConfig();
    private RoutingConfig routing = new RoutingConfig();

    // --- Nested config classes ---

    public static class ChainConfig {
        // Core node RPC port is 19585 (see nexus-core application.properties:
        // server.port=${SERVER_PORT:19585}). Override via NEX_CHAIN_RPC_URL.
        private String rpcUrl = "http://localhost:19585";
        private int chainId = 1;
        private int confirmations = 12;
        /** Dev-only: skip real chain confirmation when node is unreachable. */
        private boolean skipConfirmation = false;

        public String getRpcUrl() { return rpcUrl; }
        public void setRpcUrl(String rpcUrl) { this.rpcUrl = rpcUrl; }
        public int getChainId() { return chainId; }
        public void setChainId(int chainId) { this.chainId = chainId; }
        public int getConfirmations() { return confirmations; }
        public void setConfirmations(int confirmations) { this.confirmations = confirmations; }
        public boolean isSkipConfirmation() { return skipConfirmation; }
        public void setSkipConfirmation(boolean skipConfirmation) { this.skipConfirmation = skipConfirmation; }
    }

    /**
     * Consortium (PoA sidechain) node configuration.
     *
     * <p>Consortium node listens on 8080 by default (see nexus-consortium
     * application.yml: {@code server.port: '8080'}). Override via
     * {@code NEX_CONSORTIUM_RPC_URL}. The consortium chain uses PoA consensus
     * with ~30s block interval, so the default confirmation count is lower
     * than core's PoW + 12.</p>
     */
    public static class ConsortiumConfig {
        private String rpcUrl = "http://localhost:8080";
        private int chainId = 2;
        private int confirmations = 3;
        /** Dev-only: skip real consortium confirmation when node is unreachable. */
        private boolean skipConfirmation = false;

        public String getRpcUrl() { return rpcUrl; }
        public void setRpcUrl(String rpcUrl) { this.rpcUrl = rpcUrl; }
        public int getChainId() { return chainId; }
        public void setChainId(int chainId) { this.chainId = chainId; }
        public int getConfirmations() { return confirmations; }
        public void setConfirmations(int confirmations) { this.confirmations = confirmations; }
        public boolean isSkipConfirmation() { return skipConfirmation; }
        public void setSkipConfirmation(boolean skipConfirmation) { this.skipConfirmation = skipConfirmation; }
    }

    public static class ExchangeWalletConfig {
        private String baseUrl = "http://localhost:8081";
        private String transferPath = "/api/v1/transfers";
        private String signPath = "/api/v1/transfers/sign";
        private int timeoutMs = 10000;
        /**
         * Platform (hot-wallet) public key used as the payer when the gateway
         * delegates on-chain settlement signing to exchange-wallet. The matching
         * private key stays inside exchange-wallet's keystore and is NEVER exposed
         * to the gateway. MUST be configured per deployment.
         */
        private String platformPubkey = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getTransferPath() { return transferPath; }
        public void setTransferPath(String transferPath) { this.transferPath = transferPath; }
        public String getSignPath() { return signPath; }
        public void setSignPath(String signPath) { this.signPath = signPath; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public String getPlatformPubkey() { return platformPubkey; }
        public void setPlatformPubkey(String platformPubkey) { this.platformPubkey = platformPubkey; }
    }

    public static class WebhookConfig {
        private String callbackUrl;
        private String callbackSecret;

        public String getCallbackUrl() { return callbackUrl; }
        public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
        public String getCallbackSecret() { return callbackSecret; }
        public void setCallbackSecret(String callbackSecret) { this.callbackSecret = callbackSecret; }
    }

    public static class CheckoutConfig {
        private String baseUrl = "http://localhost:8080/api/v1/checkout";
        private int orderExpiryMinutes = 30;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public int getOrderExpiryMinutes() { return orderExpiryMinutes; }
        public void setOrderExpiryMinutes(int orderExpiryMinutes) { this.orderExpiryMinutes = orderExpiryMinutes; }
    }

    public static class SubscriptionConfig {
        private int maxRetry = 3;
        private int retryIntervalMinutes = 60;

        public int getMaxRetry() { return maxRetry; }
        public void setMaxRetry(int maxRetry) { this.maxRetry = maxRetry; }
        public int getRetryIntervalMinutes() { return retryIntervalMinutes; }
        public void setRetryIntervalMinutes(int retryIntervalMinutes) { this.retryIntervalMinutes = retryIntervalMinutes; }
    }

    /**
     * Routing configuration for the orchestration engine.
     *
     * <p>Holds the dual-chain routing policy that splits traffic between the
     * public core mainnet (large/ final settlement) and the PoA consortium
     * sidechain (small/ high-frequency payments).</p>
     */
    public static class RoutingConfig {
        private DualChainConfig dualChain = new DualChainConfig();
        private AiRoutingConfig ai = new AiRoutingConfig();

        public DualChainConfig getDualChain() { return dualChain; }
        public void setDualChain(DualChainConfig dualChain) { this.dualChain = dualChain; }

        public AiRoutingConfig getAi() { return ai; }
        public void setAi(AiRoutingConfig ai) { this.ai = ai; }
    }

    /**
     * AI 路由配置（P4-T4）。
     *
     * <p>在现有规则路由（priority/weight/cost/explicit）之上增加 AI 路由策略，
     * 通过 A/B 测试框架按比例分流，对比 AI 组与规则组的成功率/延迟。</p>
     */
    public static class AiRoutingConfig {
        private boolean enabled = false;
        /** 模型类型：heuristic（默认，无外部依赖）/ linear / external。 */
        private String modelType = "heuristic";
        private AbTestConfig abTest = new AbTestConfig();
        private AiMetricsConfig metrics = new AiMetricsConfig();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getModelType() { return modelType; }
        public void setModelType(String modelType) { this.modelType = modelType; }
        public AbTestConfig getAbTest() { return abTest; }
        public void setAbTest(AbTestConfig abTest) { this.abTest = abTest; }
        public AiMetricsConfig getMetrics() { return metrics; }
        public void setMetrics(AiMetricsConfig metrics) { this.metrics = metrics; }
    }

    /**
     * A/B 测试配置：控制 AI 路由流量比例。
     */
    public static class AbTestConfig {
        private boolean enabled = false;
        /** 分配到 AI 路由的流量百分比，范围 [0, 100]。 */
        private int aiTrafficPercentage = 10;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getAiTrafficPercentage() { return aiTrafficPercentage; }
        public void setAiTrafficPercentage(int aiTrafficPercentage) { this.aiTrafficPercentage = aiTrafficPercentage; }
    }

    /**
     * AI 路由指标收集配置：滑动窗口参数。
     */
    public static class AiMetricsConfig {
        /** 滑动窗口大小（每个 connector 保留的最近事件数）。 */
        private int windowSize = 1000;
        /** 模型推理所需的最小样本数（冷启动保护）。 */
        private int minSamples = 10;
        /** 单桶时长（ISO-8601 duration，如 PT60S = 60s、PT1M = 1 分钟）。默认 PT1M。 */
        private String bucketSize = "PT1M";

        public int getWindowSize() { return windowSize; }
        public void setWindowSize(int windowSize) { this.windowSize = windowSize; }
        public int getMinSamples() { return minSamples; }
        public String getBucketSize() { return bucketSize; }
        public void setBucketSize(String bucketSize) { this.bucketSize = bucketSize; }
        public void setMinSamples(int minSamples) { this.minSamples = minSamples; }
    }

    /**
     * Dual-chain routing policy.
     *
     * <p>When enabled, payments below {@code smallAmountThreshold} are routed to
     * the consortium sidechain first (low-latency PoA finality), while payments
     * at or above the threshold are routed to the core mainnet first (public
     * settlement security). Failover stays within the same preferred group:
     * small payments fall back from consortium to chain, large payments fall
     * back from chain to consortium.</p>
     */
    public static class DualChainConfig {
        private boolean enabled = false;
        /** Payments with amount < this threshold go to consortium first. */
        private long smallAmountThreshold = 1000000L;
        /** High-frequency window (e.g. "60s"); reserved for future frequency-based routing. */
        private String highFrequencyWindow = "60s";
        /** Preferred connectors for small-amount payments (in failover order). */
        private java.util.List<String> small = java.util.List.of("consortium", "chain");
        /** Preferred connectors for large-amount payments (in failover order). */
        private java.util.List<String> large = java.util.List.of("chain", "consortium");

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getSmallAmountThreshold() { return smallAmountThreshold; }
        public void setSmallAmountThreshold(long smallAmountThreshold) { this.smallAmountThreshold = smallAmountThreshold; }
        public String getHighFrequencyWindow() { return highFrequencyWindow; }
        public void setHighFrequencyWindow(String highFrequencyWindow) { this.highFrequencyWindow = highFrequencyWindow; }
        public java.util.List<String> getSmall() { return small; }
        public void setSmall(java.util.List<String> small) { this.small = small; }
        public java.util.List<String> getLarge() { return large; }
        public void setLarge(java.util.List<String> large) { this.large = large; }
    }

    // --- Getters and Setters ---

    public String getTokenSymbol() { return tokenSymbol; }
    public void setTokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; }

    public ChainConfig getChain() { return chain; }
    public void setChain(ChainConfig chain) { this.chain = chain; }

    public ConsortiumConfig getConsortium() { return consortium; }
    public void setConsortium(ConsortiumConfig consortium) { this.consortium = consortium; }

    public ExchangeWalletConfig getExchangeWallet() { return exchangeWallet; }
    public void setExchangeWallet(ExchangeWalletConfig exchangeWallet) { this.exchangeWallet = exchangeWallet; }

    public WebhookConfig getWebhook() { return webhook; }
    public void setWebhook(WebhookConfig webhook) { this.webhook = webhook; }

    public CheckoutConfig getCheckout() { return checkout; }
    public void setCheckout(CheckoutConfig checkout) { this.checkout = checkout; }

    public SubscriptionConfig getSubscription() { return subscription; }
    public void setSubscription(SubscriptionConfig subscription) { this.subscription = subscription; }

    public RoutingConfig getRouting() { return routing; }
    public void setRouting(RoutingConfig routing) { this.routing = routing; }
}
