package org.nexus.gateway.orchestration.connectors;

import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.*;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chain Connector - settles payments on the NexusChain blockchain.
 *
 * <p>Per the documented architecture ("Gateway 不直接构造链上交易，而是复用
 * nexus-exchange-wallet 模块的转账构造与签名链路"), this connector does NOT build
 * or sign transactions itself. It delegates settlement to the signing service's
 * signing endpoint（Feign 调用 nexus-signing-service，默认 {@code /api/v1/transfers/sign}），
 * 由签名服务构造、签名（使用服务端 keystore）并广播交易。地址转换委托给钱包管理服务
 * （Feign 调用 nexus-wallet-service）。The gateway only polls confirmation via
 * the core RPC client.</p>
 *
 * <p>This replaces the previous implementation that emitted an unsigned, fake
 * pipe-format hex directly to core's {@code /sendTransaction}. That hex was
 * rejected by core's {@code verifyTransfer}/{@code SignatureRule} and never
 * landed on-chain. See NexusChain 全维度审计与改进路线图 (v2) for the residual.</p>
 *
 * <p>Price oracle integration: when an {@link OraclePriceAdapter} is available
 * (nexus-oracle assembled into the container), the fiat amount from the payment
 * request is converted to the on-chain token amount via the oracle price
 * ({@code chainAmount = fiatAmount / oraclePrice}). If the oracle is absent or
 * the conversion fails, the original request amount is used — preserving the
 * legacy behavior and not breaking existing tests.</p>
 *
 * <p>Phase 1 任务 #55：原注入 {@code ExchangeWalletClient} 兼容委托层，改为直接
 * 注入 {@link SigningServiceFeignClient}（签名类操作）+ {@link WalletMgmtFeignClient}
 * （地址类操作），通过 Nacos 服务发现 + OpenFeign 声明式调用独立部署的微服务。</p>
 */
@Component
public class ChainConnector implements PaymentConnector {

    private static final Logger log = LoggerFactory.getLogger(ChainConnector.class);

    private final ChainRpcClient chainRpc;
    /** 签名服务 Feign 客户端：签名 + 广播（涉及私钥的操作） */
    private final SigningServiceFeignClient signingServiceClient;
    /** 钱包管理服务 Feign 客户端：地址转公钥哈希等（不涉及私钥的操作） */
    private final WalletMgmtFeignClient walletMgmtClient;
    private final GatewayConfig gatewayConfig;

    /** 可选依赖：nexus-oracle 价格适配器，用于法币→链上币换算。未装配时为 null。 */
    private final OraclePriceAdapter oraclePriceAdapter;

    private final Map<String, PaymentStatus> pendingPayments = new ConcurrentHashMap<>();
    private final Map<String, String> txHashMap = new ConcurrentHashMap<>();
    /** payee (merchant) pubkeyHash per connectorPaymentId, for confirmation/refund. */
    private final Map<String, String> payeeHashMap = new ConcurrentHashMap<>();
    /** payer pubkeyHash per connectorPaymentId, preferred refund target. */
    private final Map<String, String> payerHashMap = new ConcurrentHashMap<>();

    /**
     * 主构造函数：Spring 装配时使用，注入可选的 {@link OraclePriceAdapter}。
     *
     * @param chainRpc              链 RPC 客户端
     * @param signingServiceClient  签名服务 Feign 客户端
     * @param walletMgmtClient      钱包管理服务 Feign 客户端
     * @param gatewayConfig         网关配置
     * @param oraclePriceAdapter    价格预言机适配器（可选，可为 null）
     */
    @Autowired
    public ChainConnector(ChainRpcClient chainRpc,
                          SigningServiceFeignClient signingServiceClient,
                          WalletMgmtFeignClient walletMgmtClient,
                          GatewayConfig gatewayConfig,
                          @Autowired(required = false) OraclePriceAdapter oraclePriceAdapter) {
        this.chainRpc = chainRpc;
        this.signingServiceClient = signingServiceClient;
        this.walletMgmtClient = walletMgmtClient;
        this.gatewayConfig = gatewayConfig;
        this.oraclePriceAdapter = oraclePriceAdapter;
    }

    /**
     * 兼容构造函数：无价格预言机，保留以兼容既有单元测试（4 参数构造）。
     */
    public ChainConnector(ChainRpcClient chainRpc,
                          SigningServiceFeignClient signingServiceClient,
                          WalletMgmtFeignClient walletMgmtClient,
                          GatewayConfig gatewayConfig) {
        this(chainRpc, signingServiceClient, walletMgmtClient, gatewayConfig, null);
    }

    @Override
    public String getId() { return "chain"; }

    @Override
    public String getType() { return "chain"; }

    @Override
    public String getDisplayName() { return "NexusChain On-Chain Settlement"; }

    @Override
    public boolean isActive() { return true; }

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        try {
            String platformPubkey = gatewayConfig.getExchangeWallet().getPlatformPubkey();
            if (platformPubkey == null || platformPubkey.isBlank()) {
                return ConnectorPaymentResult.fail("exchange-wallet platform pubkey not configured");
            }
            String toPubkeyHash = walletMgmtClient.addressToPubkeyHash(request.getPayeeAddress());
            if (toPubkeyHash == null) {
                return ConnectorPaymentResult.fail("invalid payee address: " + request.getPayeeAddress());
            }

            // Resolve the on-chain token amount: when a PriceOracle is available and the
            // request currency is a fiat (not the native NEX), convert fiat -> chain token
            // via the oracle price. Falls back to the raw request amount when the oracle
            // is absent, the currency is already the chain token, or conversion fails.
            BigDecimal settlementAmount = resolveChainAmount(request);

            // Delegate construction + signing + broadcast to exchange-wallet. The returned
            // txHash is the real on-chain transaction hash (already signed by exchange-wallet).
            String txHash = signingServiceClient.signTransfer(platformPubkey, toPubkeyHash, settlementAmount);
            if (txHash == null) {
                return ConnectorPaymentResult.fail("exchange-wallet signing failed");
            }

            String connectorId = "chain_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            pendingPayments.put(connectorId, PaymentStatus.PROCESSING);
            payeeHashMap.put(connectorId, toPubkeyHash);
            String payerHash = walletMgmtClient.addressToPubkeyHash(request.getPayerAddress());
            if (payerHash != null) {
                payerHashMap.put(connectorId, payerHash);
            }
            txHashMap.put(connectorId, txHash);
            log.info("Chain payment submitted (delegated to exchange-wallet): {} -> txHash={}", connectorId, txHash);
            return ConnectorPaymentResult.ok(connectorId, PaymentStatus.PROCESSING, txHash);
        } catch (Exception e) {
            log.error("Chain payment failed: {}", e.getMessage());
            return ConnectorPaymentResult.fail("Chain settlement error: " + e.getMessage());
        }
    }

    /**
     * 解析链上币结算金额。
     *
     * <p>当 {@link OraclePriceAdapter} 可用且请求币种非链上本币（NEX）时，
     * 用法币金额÷oracle 价格换算链上币金额；否则回退到请求原始金额。
     *
     * @param request 支付请求
     * @return 链上币结算金额（始终非 null）
     */
    private BigDecimal resolveChainAmount(ConnectorPaymentRequest request) {
        BigDecimal fiatAmount = BigDecimal.valueOf(request.getAmount());
        String currency = request.getCurrency();
        // 链上本币无需换算；仅当币种非 NEX 且 oracle 可用时尝试换算
        if (oraclePriceAdapter != null && currency != null && !currency.isBlank()
                && !"NEX".equalsIgnoreCase(currency)) {
            BigDecimal converted = oraclePriceAdapter.convertToChainAmount(fiatAmount, "NEX");
            if (converted != null && converted.compareTo(BigDecimal.ZERO) > 0) {
                log.info("Fiat->chain amount converted via oracle: fiat={} {} -> chain={} NEX",
                        fiatAmount, currency, converted);
                return converted;
            }
            log.debug("Oracle conversion unavailable, falling back to raw amount: {} {}", fiatAmount, currency);
        }
        return fiatAmount;
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        PaymentStatus cached = pendingPayments.get(connectorPaymentId);
        if (cached == null) return PaymentStatus.FAILED;
        if (cached != PaymentStatus.PROCESSING) return cached;

        // Poll chain for confirmation
        String txHash = txHashMap.get(connectorPaymentId);
        if (txHash != null) {
            try {
                boolean confirmed = chainRpc.isTransactionConfirmed(txHash);
                if (confirmed) {
                    pendingPayments.put(connectorPaymentId, PaymentStatus.SUCCEEDED);
                    log.info("Chain payment confirmed: {}", connectorPaymentId);
                    return PaymentStatus.SUCCEEDED;
                }
            } catch (Exception e) {
                log.warn("Failed to query chain confirmation: {}", e.getMessage());
            }
        }
        return PaymentStatus.PROCESSING;
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        try {
            String platformPubkey = gatewayConfig.getExchangeWallet().getPlatformPubkey();
            if (platformPubkey == null || platformPubkey.isBlank()) {
                return ConnectorRefundResult.fail("exchange-wallet platform pubkey not configured");
            }
            // Refund returns funds to the original payer when known; otherwise to the payee.
            // TODO(business): confirm refund direction policy with product before production.
            String targetHash = payerHashMap.get(connectorPaymentId);
            if (targetHash == null) {
                targetHash = payeeHashMap.get(connectorPaymentId);
            }
            if (targetHash == null) {
                return ConnectorRefundResult.fail("original payment not found: " + connectorPaymentId);
            }
            String txHash = signingServiceClient.signTransfer(platformPubkey, targetHash, BigDecimal.valueOf(amount));
            if (txHash == null) {
                return ConnectorRefundResult.fail("exchange-wallet refund signing failed");
            }
            pendingPayments.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok("chain_refund_" + connectorPaymentId);
        } catch (Exception e) {
            return ConnectorRefundResult.fail("Chain refund failed: " + e.getMessage());
        }
    }

    @Override
    public ConnectorHealth healthCheck() {
        long start = System.currentTimeMillis();
        try {
            long height = chainRpc.getBlockHeight();
            long latency = System.currentTimeMillis() - start;
            if (height > 0) {
                return ConnectorHealth.up(getId(), latency);
            }
            return ConnectorHealth.down(getId(), "Chain height is " + height);
        } catch (Exception e) {
            return ConnectorHealth.down(getId(), "RPC unreachable: " + e.getMessage());
        }
    }

    @Override
    public Set<String> supportedCurrencies() { return Set.of("NEX"); }

    @Override
    public int feeBasisPoints() { return 5; }
}
