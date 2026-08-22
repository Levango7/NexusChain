package org.nexus.gateway.service;

import org.nexus.gateway.PaymentService;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.compliance.AmlResult;
import org.nexus.gateway.compliance.ComplianceService;
import org.nexus.gateway.compliance.KycStatus;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.dto.PaymentResult;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.model.Refund;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.repository.RefundRepository;
import org.nexus.gateway.risk.PaymentRequest;
import org.nexus.gateway.risk.PaymentRiskService;
import org.nexus.gateway.risk.RefundRequest;
import org.nexus.gateway.risk.RiskDecision;
import org.nexus.common.tracing.BusinessSpan;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.tracing.Tracer;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.nexus.gateway.event.PaymentConfirmedEvent;
import org.nexus.gateway.security.KeyManager;
import org.nexus.gateway.event.RefundCompletedEvent;
import org.nexus.gateway.model.OrderStateMachine;
import org.nexus.gateway.execution.ExecutionRequest;
import org.nexus.gateway.execution.OnChainResult;
import org.nexus.gateway.execution.ThreePhaseExecutionTemplate;
import org.nexus.analytics.event.PaymentCompletedEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 支付服务实现：发起 / 确认 / 退款。
 *
 * <p>P3-T5：在支付确认（payment.confirm → payment.onchain.check → payment.aml.screen）
 * 与退款（payment.refund → payment.signing.orchestrate）链路添加业务 span。
 * span 树结构见 docs/tracing-business-span.md。</p>
 */
@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final PaymentOrderRepository orderRepository;
    private final RefundRepository refundRepository;
    private final GatewayConfig gatewayConfig;
    private final ChainRpcClient chainRpcClient;
    /** 签名服务 Feign 客户端：签名 + 广播（涉及私钥的操作，退款转账） */
    private final SigningServiceFeignClient signingServiceClient;
    /** 钱包管理服务 Feign 客户端：地址转公钥哈希等（不涉及私钥的操作） */
    private final WalletMgmtFeignClient walletMgmtClient;
    private final ApplicationEventPublisher eventPublisher;
    private final KeyManager keyManager;
    private final PaymentRiskService riskService;
    private final ComplianceService complianceService;
    /** Micrometer Tracer：P3-T5 业务 span 注入。可为 null（测试环境降级 no-op）。 */
    private final Tracer tracer;
    /** P2-F3：三阶段执行模板（落库 PENDING → 链上执行 → 更新 CONFIRMED/FAILED） */
    private final ThreePhaseExecutionTemplate threePhaseTemplate;

    @Autowired
    public PaymentServiceImpl(PaymentOrderRepository orderRepository,
                              RefundRepository refundRepository,
                              GatewayConfig gatewayConfig,
                              ChainRpcClient chainRpcClient,
                              SigningServiceFeignClient signingServiceClient,
                              WalletMgmtFeignClient walletMgmtClient,
                              ApplicationEventPublisher eventPublisher,
                              KeyManager keyManager,
                              PaymentRiskService riskService,
                              ComplianceService complianceService,
                              Tracer tracer,
                              ThreePhaseExecutionTemplate threePhaseTemplate) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.gatewayConfig = gatewayConfig;
        this.chainRpcClient = chainRpcClient;
        this.signingServiceClient = signingServiceClient;
        this.walletMgmtClient = walletMgmtClient;
        this.eventPublisher = eventPublisher;
        this.keyManager = keyManager;
        this.riskService = riskService;
        this.complianceService = complianceService;
        this.tracer = tracer;
        this.threePhaseTemplate = threePhaseTemplate;
    }

    /**
     * 测试用兼容构造器：不注入 Tracer 与三阶段模板，业务 span 降级为 no-op。
     *
     * <p>注意：此构造器不注入 {@link ThreePhaseExecutionTemplate}，
     * 仅供不涉及 refund() 三阶段执行的测试使用。refund() 在此构造器下会抛出
     * {@code IllegalStateException}（threePhaseTemplate 为 null）。
     * 涉及 refund() 的测试应使用注入三阶段模板的构造器。</p>
     */
    public PaymentServiceImpl(PaymentOrderRepository orderRepository,
                              RefundRepository refundRepository,
                              GatewayConfig gatewayConfig,
                              ChainRpcClient chainRpcClient,
                              SigningServiceFeignClient signingServiceClient,
                              WalletMgmtFeignClient walletMgmtClient,
                              ApplicationEventPublisher eventPublisher,
                              KeyManager keyManager,
                              PaymentRiskService riskService,
                              ComplianceService complianceService) {
        this(orderRepository, refundRepository, gatewayConfig, chainRpcClient,
                signingServiceClient, walletMgmtClient, eventPublisher, keyManager,
                riskService, complianceService, null, null);
    }

    /**
     * 测试用兼容构造器：注入 Tracer 但不注入三阶段模板。
     */
    public PaymentServiceImpl(PaymentOrderRepository orderRepository,
                              RefundRepository refundRepository,
                              GatewayConfig gatewayConfig,
                              ChainRpcClient chainRpcClient,
                              SigningServiceFeignClient signingServiceClient,
                              WalletMgmtFeignClient walletMgmtClient,
                              ApplicationEventPublisher eventPublisher,
                              KeyManager keyManager,
                              PaymentRiskService riskService,
                              ComplianceService complianceService,
                              Tracer tracer) {
        this(orderRepository, refundRepository, gatewayConfig, chainRpcClient,
                signingServiceClient, walletMgmtClient, eventPublisher, keyManager,
                riskService, complianceService, tracer, null);
    }

    @Override
    @Transactional
    public PaymentResult initiatePayment(Long orderId, String payerAddress) {
        PaymentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        if (order.getStatus() != PaymentOrder.OrderStatus.PENDING) {
            return PaymentResult.failed(order.getOrderNo(), "Order is not in PENDING status");
        }

        if (order.getExpiresAt().isBefore(LocalDateTime.now())) {
            OrderStateMachine.transition(order, PaymentOrder.OrderStatus.EXPIRED);
            orderRepository.save(order);
            return PaymentResult.failed(order.getOrderNo(), "Order has expired");
        }

        // Risk gate: evaluate before entering PAYING. REJECTED/FROZEN -> FAILED (terminal for this attempt);
        // PENDING_REVIEW is logged and allowed through until a review queue exists.
        PaymentRequest riskRequest = new PaymentRequest(
                order.getMerchantId(), payerAddress, order.getAmount(), order.getTokenSymbol());
        riskRequest.setIdempotencyKey(order.getOrderNo());
        RiskDecision riskDecision = riskService.evaluatePayment(riskRequest);
        if (riskDecision == RiskDecision.REJECTED || riskDecision == RiskDecision.FROZEN) {
            OrderStateMachine.transition(order, PaymentOrder.OrderStatus.FAILED);
            orderRepository.save(order);
            log.warn("Payment rejected by risk control: orderNo={}, merchantId={}, decision={}",
                    order.getOrderNo(), order.getMerchantId(), riskDecision);
            return PaymentResult.failed(order.getOrderNo(), "Payment rejected by risk control (" + riskDecision + ")");
        }
        if (riskDecision == RiskDecision.PENDING_REVIEW) {
            log.info("Payment flagged for manual risk review: orderNo={}, merchantId={}",
                    order.getOrderNo(), order.getMerchantId());
        }

        // KYC observability: record payer verification level (non-blocking until KYC data exists)
        KycStatus kycStatus = complianceService.checkKyc(payerAddress);
        log.info("Payer KYC status: payer={}, status={}", payerAddress, kycStatus);

        order.setPayerAddress(payerAddress);
        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PAYING);
        orderRepository.save(order);

        String checkoutUrl = gatewayConfig.getCheckout().getBaseUrl() + "/" + order.getCheckoutToken();
        log.info("Payment initiated: orderNo={}, payer={}", order.getOrderNo(), payerAddress);

        PaymentResult result = PaymentResult.pending(order.getOrderNo(), checkoutUrl);
        result.setAmount(order.getAmount());
        result.setTokenSymbol(order.getTokenSymbol());
        result.setPayerAddress(payerAddress);
        result.setPayeeAddress(order.getPayeeAddress());
        return result;
    }

    @Override
    @Transactional
    public PaymentResult confirmPayment(Long orderId, String chainTxHash) {
        // P3-T5：支付确认主 span（payment.confirm）
        try (BusinessSpan confirmSpan = BusinessSpan.start(tracer, "payment.confirm")
                .attr("payment.order.id", orderId)
                .attr("payment.tx.hash", chainTxHash)) {
            PaymentOrder order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            confirmSpan.attr("payment.order.no", order.getOrderNo())
                    .attr("payment.amount", order.getAmount());

            if (order.getStatus() == PaymentOrder.OrderStatus.PAID) {
                return PaymentResult.success(order.getOrderNo(), order.getChainTxHash(), order.getPaidAt());
            }

            // P3-T5：上链确认 span（payment.onchain.check）
            boolean confirmed;
            try (BusinessSpan onchainSpan = BusinessSpan.start(tracer, "payment.onchain.check")
                    .attr("payment.tx.hash", chainTxHash)) {
                confirmed = isChainConfirmed(chainTxHash);
                onchainSpan.attr("payment.onchain.confirmed", confirmed);
            }
            if (!confirmed) {
                return PaymentResult.failed(order.getOrderNo(), "Transaction not yet confirmed on chain");
            }

            // P0-4 安全加固：交易-订单绑定校验
            // 防止攻击者通过虚假 txHash 绕过链上确认（尤其是 skip-confirmation=true 时
            // 任何 >=16 字符字符串都被当作已确认的 fallback 逻辑）。
            // TODO: 后续接入完整的链上交易详情查询。当前 ChainRpcClient 仅提供
            //     isTransactionConfirmed / getTransactionStatus，不含交易金额与收款人字段。
            //     需扩展 ChainRpcClient.getTransaction(txHash) 返回
            //     {amount, recipient, sender, ...}，并在此校验：
            //       - tx.amount.compareTo(order.getAmount()) == 0
            //       - tx.recipient.equals(order.getPayeeAddress())  (商户结算地址)
            //     临时校验：chainTxHash 非空且长度合理（>=16 且 <=128），
            //     并对 skip-confirmation fallback 发出警告以阻止生产环境误用。
            if (chainTxHash == null || chainTxHash.length() < 16 || chainTxHash.length() > 128) {
                log.warn("Transaction-Order binding check failed: invalid chainTxHash length for orderNo={}, txHash={}",
                        order.getOrderNo(), chainTxHash);
                OrderStateMachine.transition(order, PaymentOrder.OrderStatus.FAILED);
                order.setChainTxHash(chainTxHash);
                orderRepository.save(order);
                confirmSpan.attr("payment.status", "FAILED")
                        .attr("tx.binding.check", "INVALID_HASH")
                        .error(null);
                return PaymentResult.failed(order.getOrderNo(),
                        "Transaction does not match order: amount or recipient mismatch");
            }
            if (gatewayConfig.getChain().isSkipConfirmation()) {
                // skip-confirmation fallback（任何 >=16 字符字符串都被当作已确认）仅限开发模式。
                // 生产环境必须设置 nexus.chain.skip-confirmation=false，否则攻击者可凭虚假 txHash
                // 确认任意订单。此处仅记录警告，完整防护依赖后续接入的交易详情校验（见上方 TODO）。
                log.warn("SECURITY: skip-confirmation=true detected in confirmPayment; orderNo={}, txHash={}. "
                        + "This accepts any well-formed txHash as confirmed. "
                        + "MUST be disabled in production (nexus.chain.skip-confirmation=false).",
                        order.getOrderNo(), chainTxHash);
            }

            // AML gate: screen the confirmed transaction before marking it PAID.
            // High-risk hits (score >= 90 or manual review required) block the payment
            // and file a Suspicious Activity Report; lower scores pass with a warning log.
            org.nexus.gateway.compliance.Transaction amlTx = new org.nexus.gateway.compliance.Transaction(
                    order.getOrderNo(), order.getAmount(), order.getTokenSymbol());
            amlTx.setMerchantId(order.getMerchantId());
            amlTx.setFromAddress(order.getPayerAddress());
            amlTx.setToAddress(order.getPayeeAddress());
            amlTx.setChainTxHash(chainTxHash);

            // P3-T5：AML 筛查 span（payment.aml.screen）
            AmlResult amlResult;
            try (BusinessSpan amlSpan = BusinessSpan.start(tracer, "payment.aml.screen")
                    .attr("payment.order.no", order.getOrderNo())
                    .attr("payment.tx.hash", chainTxHash)) {
                amlResult = complianceService.screenAml(amlTx);
                if (amlResult != null && amlResult.getRiskScore() != null) {
                    amlSpan.attr("aml.risk.score", amlResult.getRiskScore());
                }
            }
            boolean amlBlock = amlResult != null
                    && (Boolean.TRUE.equals(amlResult.getNeedsManualReview())
                        || (amlResult.getRiskScore() != null && amlResult.getRiskScore() >= 90));
            if (amlBlock) {
                complianceService.reportSuspicious(amlTx,
                        "AML screening block: score=" + amlResult.getRiskScore()
                                + ", hits=" + amlResult.getHitLists());
                OrderStateMachine.transition(order, PaymentOrder.OrderStatus.FAILED);
                order.setChainTxHash(chainTxHash);
                orderRepository.save(order);
                log.warn("Payment blocked by AML screening: orderNo={}, txHash={}, score={}",
                        order.getOrderNo(), chainTxHash, amlResult.getRiskScore());
                confirmSpan.attr("payment.status", "FAILED")
                        .attr("aml.blocked", true)
                        .error(null);
                return PaymentResult.failed(order.getOrderNo(), "Payment blocked by compliance screening");
            }

            order.setChainTxHash(chainTxHash);
            OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PAID);
            order.setPaidAt(LocalDateTime.now());
            orderRepository.save(order);

            log.info("Payment confirmed: orderNo={}, txHash={}", order.getOrderNo(), chainTxHash);
            confirmSpan.attr("payment.status", "PAID").success();

            // Publish event for async webhook notification
            eventPublisher.publishEvent(new PaymentConfirmedEvent(
                    this, order.getId(), order.getOrderNo(), order.getMerchantId(),
                    chainTxHash, order.getPayerAddress(), order.getAmount().toPlainString()));

            // Publish PaymentCompletedEvent for nexus-analytics collection.
            // currency is filled with the order token symbol (chain-side unit);
            // connector identifies the on-chain settlement channel (NexusChain core node).
            eventPublisher.publishEvent(new PaymentCompletedEvent(
                    this,
                    order.getId(),
                    order.getAmount(),
                    order.getTokenSymbol(),
                    "NEXUS-CORE",
                    order.getMerchantId(),
                    chainTxHash,
                    order.getPayerAddress(),
                    order.getPayeeAddress(),
                    Instant.now()));

            return PaymentResult.success(order.getOrderNo(), chainTxHash, order.getPaidAt());
        }
    }

    @Override
    @GlobalTransactional(timeoutMills = 120000)
    public Refund refund(Long orderId, BigDecimal amount, String reason) {
        // P3-T5：退款主 span（payment.refund）
        try (BusinessSpan refundSpan = BusinessSpan.start(tracer, "payment.refund")
                .attr("payment.order.id", orderId)
                .attr("payment.refund.amount", amount)
                .attr("payment.refund.reason", reason)) {
            PaymentOrder order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

            refundSpan.attr("payment.order.no", order.getOrderNo());

            if (order.getStatus() != PaymentOrder.OrderStatus.PAID) {
                throw new IllegalStateException("Cannot refund order in status: " + order.getStatus());
            }

            if (amount.compareTo(order.getAmount()) > 0) {
                throw new IllegalArgumentException("Refund amount exceeds order amount");
            }

            // Risk gate: evaluate refund before executing the transfer.
            RefundRequest riskRequest = new RefundRequest(orderId, order.getMerchantId(), amount, reason);
            riskRequest.setReceiverAddress(order.getPayerAddress());
            RiskDecision refundDecision = riskService.evaluateRefund(riskRequest);
            if (refundDecision == RiskDecision.REJECTED || refundDecision == RiskDecision.FROZEN) {
                log.warn("Refund rejected by risk control: orderId={}, merchantId={}, decision={}",
                        orderId, order.getMerchantId(), refundDecision);
                refundSpan.attr("payment.risk.decision", refundDecision.name()).error(null);
                throw new IllegalStateException("Refund rejected by risk control (" + refundDecision + ")");
            }

            // P2-F3：三阶段补偿模式（落库 PENDING → 链上执行 → 更新 CONFIRMED/FAILED）
            // 替代 P1-F3 的内联实现，标准化事务边界与补偿语义。
            // @GlobalTransactional 协调跨服务分支（walletMgmtClient），
            // 阶段1/3 通过 ThreePhaseExecutionTemplate 内部 REQUIRES_NEW 独立提交，
            // 确保 PENDING 落库不被全局回滚，可被 CompensationService 扫描补偿。
            if (threePhaseTemplate == null) {
                throw new IllegalStateException(
                        "ThreePhaseExecutionTemplate not injected; refund() requires three-phase mode");
            }

            String refundNo = "RF" + System.currentTimeMillis()
                    + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
            refundSpan.attr("payment.refund.no", refundNo);

            // 构造三阶段执行请求（操作意图快照）
            ExecutionRequest executionRequest = new ExecutionRequest(
                    ExecutionRequest.OperationType.REFUND,
                    amount,
                    order.getPayerAddress(),
                    order.getPayeeAddress(),
                    refundNo,  // 幂等键：退款单号唯一
                    order.getTokenSymbol(),
                    String.valueOf(orderId));

            // 三阶段执行：阶段1 落库 PENDING → 阶段2 链上转账 → 阶段3 更新 CONFIRMED/FAILED
            Refund result = threePhaseTemplate.execute(
                    executionRequest,
                    // 阶段1：落库 PENDING（Refund + 订单状态 REFUND_PENDING）
                    req -> {
                        Refund refund = new Refund();
                        refund.setRefundNo(refundNo);
                        refund.setOrderId(orderId);
                        refund.setMerchantId(order.getMerchantId());
                        refund.setAmount(amount);
                        refund.setTokenSymbol(order.getTokenSymbol());
                        refund.setReceiverAddress(order.getPayerAddress());
                        refund.setSenderAddress(order.getPayeeAddress());
                        refund.setReason(reason);
                        refund.setStatus(Refund.RefundStatus.PENDING);

                        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.REFUND_PENDING);
                        orderRepository.save(order);
                        return refundRepository.save(refund);
                    },
                    // 阶段2：链上执行（事务外，不可逆）
                    refund -> {
                        String receiverPubkeyHash = walletMgmtClient.addressToPubkeyHash(order.getPayerAddress());
                        if (receiverPubkeyHash == null) {
                            return OnChainResult.failure("wallet unreachable", false);
                        }
                        String txHash = executeRefundTransfer(order, receiverPubkeyHash, amount);
                        if (txHash == null) {
                            return OnChainResult.failure("refund transfer failed", false);
                        }
                        return OnChainResult.success(txHash, false);
                    },
                    // 阶段3：根据链上结果更新 CONFIRMED/FAILED
                    (refund, onChainResult) -> {
                        // 重新加载 order 以获取最新的乐观锁版本号。
                        // 阶段1 在 REQUIRES_NEW 独立事务中已提交并递增了 version，
                        // 但 JPA merge 不会回写原始 detached order 对象的 version，
                        // 直接复用会导致阶段3 的 save 触发 ObjectOptimisticLockingFailureException。
                        PaymentOrder latestOrder = orderRepository.findById(orderId)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Order not found after refund phase 1: " + orderId));
                        if (onChainResult.isSuccess()) {
                            refund.setChainTxHash(onChainResult.getTxHash());
                            refund.setStatus(Refund.RefundStatus.COMPLETED);
                            refund.setCompletedAt(LocalDateTime.now());
                            OrderStateMachine.transition(latestOrder, PaymentOrder.OrderStatus.REFUNDED);
                            refundSpan.attr("payment.refund.tx.hash", onChainResult.getTxHash())
                                    .attr("payment.refund.status", "COMPLETED");
                        } else {
                            refund.setStatus(Refund.RefundStatus.FAILED);
                            // 链上转账失败：回滚订单状态到 PAID，允许后续重试退款
                            // refund 失败 → 资金未转出，无需链上补偿
                            OrderStateMachine.transition(latestOrder, PaymentOrder.OrderStatus.PAID);
                            refundSpan.attr("payment.refund.status", "FAILED").error(null);
                            log.error("Refund transfer failed for order: {}, reason: {}",
                                    latestOrder.getOrderNo(), onChainResult.getError());
                        }
                        orderRepository.save(latestOrder);
                        Refund saved = refundRepository.save(refund);

                        // Publish event only for genuinely completed refunds
                        if (saved.getStatus() == Refund.RefundStatus.COMPLETED) {
                            eventPublisher.publishEvent(new RefundCompletedEvent(
                                    this, latestOrder.getId(), latestOrder.getOrderNo(),
                                    latestOrder.getMerchantId(),
                                    saved.getRefundNo(), amount.toPlainString(), saved.getChainTxHash()));
                        }
                    });

            log.info("Refund processed: refundNo={}, orderId={}, status={}",
                    result.getRefundNo(), orderId, result.getStatus());
            return result;
        }
    }

    @Override
    public boolean isChainConfirmed(String chainTxHash) {
        if (chainTxHash == null || chainTxHash.isEmpty()) {
            return false;
        }
        boolean confirmed = chainRpcClient.isTransactionConfirmed(chainTxHash);
        log.debug("Chain confirmation check: txHash={}, confirmed={}", chainTxHash, confirmed);
        return confirmed;
    }

    /**
     * Execute a refund transfer via the exchange-wallet service.
     *
     * <p>In production, merchant key material comes from secure storage (HSM/KMS/Vault).
     * If the merchant keypair is not configured, the refund cannot be signed and this
     * method fails loudly with {@link IllegalStateException} — no synthetic hash is ever
     * produced. If the wallet service is unavailable during the transfer call, null is
     * returned and the caller marks the refund FAILED.</p>
     *
     * @throws IllegalStateException if the merchant keypair is not configured
     */
    private String executeRefundTransfer(PaymentOrder order, String receiverPubkeyHash, BigDecimal amount) {
        // SECURITY FIX: refunds are signed by the PLATFORM hot-wallet key held inside
        // exchange-wallet's keystore. The gateway never fetches or transmits any
        // private key (the legacy /ClientToTransferAccount?prikey= path was removed
        // server-side because it exposed private keys over HTTP).
        String platformPubkey = gatewayConfig.getExchangeWallet().getPlatformPubkey();

        if (platformPubkey == null || platformPubkey.isBlank()) {
            throw new IllegalStateException("Exchange-wallet platformPubkey not configured; "
                    + "refund cannot be signed without the platform hot-wallet key");
        }

        try {
            return signingServiceClient.signTransfer(platformPubkey, receiverPubkeyHash, amount);
        } catch (Exception e) {
            log.error("Refund transfer exception for order {}: {}", order.getOrderNo(), e.getMessage());
            return null;
        }
    }
}
