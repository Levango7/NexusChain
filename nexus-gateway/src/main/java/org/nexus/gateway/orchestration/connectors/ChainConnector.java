package org.nexus.gateway.orchestration.connectors;

import org.nexus.gateway.approval.SigningApprovalExecutionService;
import org.nexus.gateway.approval.SigningOutcome;
import org.nexus.gateway.approval.SigningTransfers;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.*;
import org.nexus.gateway.orchestration.settlement.ChainFinalityPolicy;
import org.nexus.gateway.orchestration.settlement.FinalityPolicy;
import org.nexus.gateway.orchestration.settlement.FinalityService;
import org.nexus.gateway.model.FinalityStatus;
import org.nexus.gateway.model.SubmissionStatus;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.nexus.sdk.wallet.WalletUtils;
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

    /** 最终性推导服务，用于查询链上交易的最终性状态。兼容构造器时为 null。 */
    private final FinalityService finalityService;

    /** 缓存的最终性策略实例，避免每次 queryPayment 轮询时重复创建。 */
    private final ChainFinalityPolicy finalityPolicy;

    private final Map<String, PaymentStatus> pendingPayments = new ConcurrentHashMap<>();
    private final Map<String, String> txHashMap = new ConcurrentHashMap<>();
    /** payee (merchant) pubkeyHash per connectorPaymentId, for confirmation/refund. */
    private final Map<String, String> payeeHashMap = new ConcurrentHashMap<>();
    /** payer pubkeyHash per connectorPaymentId, preferred refund target. */
    private final Map<String, String> payerHashMap = new ConcurrentHashMap<>();
    /** 审批执行链接的 requestKey per connectorPaymentId（待审批时用于状态轮询推进）。 */
    private final Map<String, String> approvalRequestKeys = new ConcurrentHashMap<>();

    /** 大额签名审批执行服务；未装配时退化为直接签名（2001 将 fail-closed）。 */
    private SigningApprovalExecutionService approvalExecutionService;

    @Autowired(required = false)
    public void setApprovalExecutionService(SigningApprovalExecutionService approvalExecutionService) {
        this.approvalExecutionService = approvalExecutionService;
    }

    /**
     * 主构造函数：Spring 装配时使用，注入可选的 {@link OraclePriceAdapter} 和 {@link FinalityService}。
     *
     * @param chainRpc              链 RPC 客户端
     * @param signingServiceClient  签名服务 Feign 客户端
     * @param walletMgmtClient      钱包管理服务 Feign 客户端
     * @param gatewayConfig         网关配置
     * @param oraclePriceAdapter    价格预言机适配器（可选，可为 null）
     * @param finalityService       最终性推导服务（可选，可为 null）
     */
    @Autowired
    public ChainConnector(ChainRpcClient chainRpc,
                          SigningServiceFeignClient signingServiceClient,
                          WalletMgmtFeignClient walletMgmtClient,
                          GatewayConfig gatewayConfig,
                          @Autowired(required = false) OraclePriceAdapter oraclePriceAdapter,
                          @Autowired(required = false) FinalityService finalityService) {
        this.chainRpc = chainRpc;
        this.signingServiceClient = signingServiceClient;
        this.walletMgmtClient = walletMgmtClient;
        this.gatewayConfig = gatewayConfig;
        this.oraclePriceAdapter = oraclePriceAdapter;
        this.finalityService = finalityService;
        this.finalityPolicy = finalityService != null ? new ChainFinalityPolicy(finalityService) : null;
    }

    /**
     * 兼容构造函数：无最终性服务，保留以兼容既有单元测试（5 参数构造）。
     */
    public ChainConnector(ChainRpcClient chainRpc,
                          SigningServiceFeignClient signingServiceClient,
                          WalletMgmtFeignClient walletMgmtClient,
                          GatewayConfig gatewayConfig,
                          OraclePriceAdapter oraclePriceAdapter) {
        this(chainRpc, signingServiceClient, walletMgmtClient, gatewayConfig, oraclePriceAdapter, null);
    }

    /**
     * 兼容构造函数：无价格预言机和最终性服务，保留以兼容既有单元测试（4 参数构造）。
     */
    public ChainConnector(ChainRpcClient chainRpc,
                          SigningServiceFeignClient signingServiceClient,
                          WalletMgmtFeignClient walletMgmtClient,
                          GatewayConfig gatewayConfig) {
        this(chainRpc, signingServiceClient, walletMgmtClient, gatewayConfig, null, null);
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
            String toPubkeyHash = WalletUtils.addressToPubkeyHash(request.getPayeeAddress());
            if (toPubkeyHash == null) {
                return ConnectorPaymentResult.fail("invalid payee address: " + request.getPayeeAddress());
            }

            // Resolve the on-chain token amount: when a PriceOracle is available and the
            // request currency is a fiat (not the native NEX), convert fiat -> chain token
            // via the oracle price. Falls back to the raw request amount when the oracle
            // is absent, the currency is already the chain token, or conversion fails.
            BigDecimal settlementAmount = resolveChainAmount(request);

            String connectorId = "chain_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            // 业务请求标识优先用编排支付 ID，保证同一笔支付重试时复用同一审批请求
            String requestKey = SigningTransfers.requestKey("CHAIN", request.getPaymentId(), connectorId);
            SigningOutcome outcome = submitTransfer(requestKey, "CHAIN",
                    platformPubkey, toPubkeyHash, settlementAmount);

            if (outcome.requiresManualVerification() || outcome.isAwaiting()) {
                // 待审批：钱未出账；结果不可核对：钱可能已出账。两种都不能记 FAILED，
                // 业务侧统一保持 PROCESSING（前者由 queryPayment 推进，后者等人工核对）
                pendingPayments.put(connectorId, PaymentStatus.PROCESSING);
                payeeHashMap.put(connectorId, toPubkeyHash);
                putPayerHash(connectorId, request.getPayerAddress());
                approvalRequestKeys.put(connectorId, requestKey);
                if (outcome.requiresManualVerification()) {
                    log.error("Chain payment execution unverified (manual verification required): "
                            + "connectorId={}, requestKey={}", connectorId, requestKey);
                } else {
                    log.info("Chain payment awaiting approval: connectorId={}, approvalId={}, amount={}",
                            connectorId, outcome.approvalId(), settlementAmount);
                }
                return ConnectorPaymentResult.ok(connectorId, PaymentStatus.PROCESSING)
                        .withSubmissionStatus(SubmissionStatus.SIGNING);
            }
            if (!outcome.isExecuted() || outcome.txHash() == null) {
                return ConnectorPaymentResult.fail("exchange-wallet signing failed: " + outcome.error());
            }

            String txHash = outcome.txHash();
            pendingPayments.put(connectorId, PaymentStatus.PROCESSING);
            payeeHashMap.put(connectorId, toPubkeyHash);
            putPayerHash(connectorId, request.getPayerAddress());
            txHashMap.put(connectorId, txHash);
            log.info("Chain payment submitted (delegated to exchange-wallet): {} -> txHash={}", connectorId, txHash);
            return ConnectorPaymentResult.ok(connectorId, PaymentStatus.PROCESSING, txHash);
        } catch (RuntimeException e) {
            log.error("Chain payment failed: {}", e.getMessage());
            return ConnectorPaymentResult.fail("Chain settlement error: " + e.getMessage());
        }
    }

    private void putPayerHash(String connectorId, String payerAddress) {
        String payerHash = WalletUtils.addressToPubkeyHash(payerAddress);
        if (payerHash != null) {
            payerHashMap.put(connectorId, payerHash);
        }
    }

    /**
     * 提交签名转账：装配了审批执行服务时统一走三态（含 approvalId 持久化与执行腿），
     * 否则直接签名（2001 视为无法跟踪的失败，fail-closed）。
     */
    private SigningOutcome submitTransfer(String requestKey, String requestType,
                                          String fromPubkey, String toPubkeyHash, BigDecimal amount) {
        return SigningTransfers.signOrAwait(approvalExecutionService, signingServiceClient,
                requestKey, requestType, fromPubkey, toPubkeyHash, amount, "NEX");
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

        // Poll chain for confirmation via FinalityPolicy
        String txHash = txHashMap.get(connectorPaymentId);
        if (txHash == null) {
            SigningOutcome outcome = advanceApproval(connectorPaymentId);
            if (outcome != null) {
                if (outcome.isFailed()) {
                    pendingPayments.put(connectorPaymentId, PaymentStatus.FAILED);
                    log.error("Chain payment approval leg failed: connectorId={}, requestKey={}, error={}",
                            connectorPaymentId, approvalRequestKeys.get(connectorPaymentId), outcome.error());
                    return PaymentStatus.FAILED;
                }
                if (outcome.isExecuted() && outcome.txHash() != null && !outcome.txHash().isBlank()) {
                    txHash = outcome.txHash();
                    txHashMap.put(connectorPaymentId, txHash);
                    log.info("Chain payment executed after approval: connectorId={}, txHash={}",
                            connectorPaymentId, txHash);
                } else {
                    if (outcome.requiresManualVerification()) {
                        log.error("Chain payment approval executed but result is unverified "
                                        + "(manual check required, retry forbidden): connectorId={}, requestKey={}",
                                connectorPaymentId, approvalRequestKeys.get(connectorPaymentId));
                    }
                    // 仍在审批中 / 执行中 / 结果待人工核对 —— 都不是失败
                    return PaymentStatus.PROCESSING;
                }
            }
        }

        FinalityPolicy policy = getFinalityPolicy();
        if (policy != null) {
            try {
                FinalityService.FinalityInfo info = policy.evaluateFinality(txHash);
                PaymentStatus mapped = mapFinalityToPaymentStatus(info.status());
                if (mapped == PaymentStatus.SUCCEEDED) {
                    pendingPayments.put(connectorPaymentId, PaymentStatus.SUCCEEDED);
                    log.info("Chain payment confirmed (finality={}): {}", info.status(), connectorPaymentId);
                }
                return mapped;
            } catch (RuntimeException e) {
                log.warn("Failed to evaluate finality: {}", e.getMessage());
            }
        } else {
            // Fallback: legacy confirmation check when FinalityPolicy unavailable
            try {
                boolean confirmed = chainRpc.isTransactionConfirmed(txHash);
                if (confirmed) {
                    pendingPayments.put(connectorPaymentId, PaymentStatus.SUCCEEDED);
                    log.info("Chain payment confirmed (legacy): {}", connectorPaymentId);
                    return PaymentStatus.SUCCEEDED;
                }
            } catch (RuntimeException e) {
                log.warn("Failed to query chain confirmation: {}", e.getMessage());
            }
        }
        return PaymentStatus.PROCESSING;
    }

    /**
     * 推进一笔"大额待审批"的链上支付：查审批状态，已批准则由本调用方执行签名端点。
     *
     * <p>执行只由该笔支付自己的状态轮询触发——不存在脱离业务记录的后台自动执行，
     * 因而不会出现"业务已判失败、审批随后把钱转走"。</p>
     *
     * @param connectorPaymentId 连接器支付 ID
     * @return 推进后的三态结果；本笔没有审批链接（直接签名路径）时返回 null
     */
    private SigningOutcome advanceApproval(String connectorPaymentId) {
        String requestKey = approvalRequestKeys.get(connectorPaymentId);
        if (requestKey == null || approvalExecutionService == null) {
            return null;
        }
        return approvalExecutionService.refresh(requestKey);
    }

    /**
     * 将 {@link FinalityStatus} 映射为 {@link PaymentStatus}。
     *
     * <ul>
     *   <li>FINALIZED → SUCCEEDED</li>
     *   <li>FINALIZING → PROCESSING</li>
     *   <li>OPTIMISTIC → PROCESSING</li>
     *   <li>UNKNOWN → PROCESSING（继续等待）</li>
     * </ul>
     */
    private PaymentStatus mapFinalityToPaymentStatus(FinalityStatus finalityStatus) {
        return switch (finalityStatus) {
            case FINALIZED -> PaymentStatus.SUCCEEDED;
            case FINALIZING, OPTIMISTIC, UNKNOWN -> PaymentStatus.PROCESSING;
        };
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        try {
            String platformPubkey = gatewayConfig.getExchangeWallet().getPlatformPubkey();
            if (platformPubkey == null || platformPubkey.isBlank()) {
                return ConnectorRefundResult.fail("exchange-wallet platform pubkey not configured");
            }
            // Refund direction policy（#18 已确认默认：优先原付款人，未知则退给收款人）。
            // 产品可经 nexus.refund.direction=payee 覆盖为"固定退收款人"；两者未知时失败。
            String refundDirection = System.getProperty("nexus.refund.direction", "payer");
            String targetHash = "payee".equalsIgnoreCase(refundDirection)
                    ? payeeHashMap.get(connectorPaymentId)
                    : payerHashMap.get(connectorPaymentId);
            if (targetHash == null && !"payee".equalsIgnoreCase(refundDirection)) {
                targetHash = payeeHashMap.get(connectorPaymentId);
            }
            if (targetHash == null) {
                return ConnectorRefundResult.fail("original payment not found: " + connectorPaymentId);
            }
            String refundId = "chain_refund_" + connectorPaymentId;
            SigningOutcome outcome = submitTransfer(
                    SigningTransfers.requestKey("CHAIN_REFUND", connectorPaymentId + ":" + amount, refundId),
                    "CHAIN_REFUND", platformPubkey, targetHash, BigDecimal.valueOf(amount));
            if (outcome.isExecuted()) {
                pendingPayments.put(connectorPaymentId, PaymentStatus.REFUNDED);
                return ConnectorRefundResult.ok(refundId);
            }
            if (outcome.isAwaiting()) {
                // 大额退款待审批：钱还没动，但也绝不能报"退款成功"或"退款失败"
                log.info("Chain refund awaiting approval: connectorId={}, approvalId={}",
                        connectorPaymentId, outcome.approvalId());
                return ConnectorRefundResult.pending(refundId, outcome.approvalId());
            }
            if (outcome.requiresManualVerification()) {
                log.error("Chain refund executed but unverified (manual check required): connectorId={}, approvalId={}",
                        connectorPaymentId, outcome.approvalId());
                return ConnectorRefundResult.pending(refundId, outcome.approvalId());
            }
            return ConnectorRefundResult.fail("exchange-wallet refund signing failed: " + outcome.error());
        } catch (RuntimeException e) {
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
        } catch (RuntimeException e) {
            return ConnectorHealth.down(getId(), "RPC unreachable: " + e.getMessage());
        }
    }

    @Override
    public Set<String> supportedCurrencies() { return Set.of("NEX"); }

    @Override
    public FinalityPolicy getFinalityPolicy() {
        return finalityPolicy;
    }

    @Override
    public int feeBasisPoints() { return 5; }
}
