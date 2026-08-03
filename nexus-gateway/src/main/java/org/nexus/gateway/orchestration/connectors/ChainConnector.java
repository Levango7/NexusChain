package org.nexus.gateway.orchestration.connectors;

import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.client.ExchangeWalletClient;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * or sign transactions itself. It delegates settlement to exchange-wallet's
 * signing endpoint (default {@code /api/v1/transfers/sign}), which constructs,
 * signs (with its own server-side keystore) and broadcasts the transaction. The
 * gateway only polls confirmation via the core RPC client.</p>
 *
 * <p>This replaces the previous implementation that emitted an unsigned, fake
 * pipe-format hex directly to core's {@code /sendTransaction}. That hex was
 * rejected by core's {@code verifyTransfer}/{@code SignatureRule} and never
 * landed on-chain. See NexusChain 全维度审计与改进路线图 (v2) for the residual.</p>
 */
@Component
public class ChainConnector implements PaymentConnector {

    private static final Logger log = LoggerFactory.getLogger(ChainConnector.class);

    private final ChainRpcClient chainRpc;
    private final ExchangeWalletClient walletClient;
    private final GatewayConfig gatewayConfig;

    private final Map<String, PaymentStatus> pendingPayments = new ConcurrentHashMap<>();
    private final Map<String, String> txHashMap = new ConcurrentHashMap<>();
    /** payee (merchant) pubkeyHash per connectorPaymentId, for confirmation/refund. */
    private final Map<String, String> payeeHashMap = new ConcurrentHashMap<>();
    /** payer pubkeyHash per connectorPaymentId, preferred refund target. */
    private final Map<String, String> payerHashMap = new ConcurrentHashMap<>();

    public ChainConnector(ChainRpcClient chainRpc, ExchangeWalletClient walletClient, GatewayConfig gatewayConfig) {
        this.chainRpc = chainRpc;
        this.walletClient = walletClient;
        this.gatewayConfig = gatewayConfig;
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
            String toPubkeyHash = walletClient.addressToPubkeyHash(request.getPayeeAddress());
            if (toPubkeyHash == null) {
                return ConnectorPaymentResult.fail("invalid payee address: " + request.getPayeeAddress());
            }

            // Delegate construction + signing + broadcast to exchange-wallet. The returned
            // txHash is the real on-chain transaction hash (already signed by exchange-wallet).
            String txHash = walletClient.signTransfer(platformPubkey, toPubkeyHash, BigDecimal.valueOf(request.getAmount()));
            if (txHash == null) {
                return ConnectorPaymentResult.fail("exchange-wallet signing failed");
            }

            String connectorId = "chain_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            pendingPayments.put(connectorId, PaymentStatus.PROCESSING);
            payeeHashMap.put(connectorId, toPubkeyHash);
            String payerHash = walletClient.addressToPubkeyHash(request.getPayerAddress());
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
            String txHash = walletClient.signTransfer(platformPubkey, targetHash, BigDecimal.valueOf(amount));
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
