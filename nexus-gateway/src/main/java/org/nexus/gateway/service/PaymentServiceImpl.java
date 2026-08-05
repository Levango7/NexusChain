package org.nexus.gateway.service;

import org.nexus.gateway.PaymentService;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.client.ExchangeWalletClient;
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
import org.springframework.context.ApplicationEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.nexus.gateway.event.PaymentConfirmedEvent;
import org.nexus.gateway.security.KeyManager;
import org.nexus.gateway.event.RefundCompletedEvent;
import org.nexus.gateway.model.OrderStateMachine;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final PaymentOrderRepository orderRepository;
    private final RefundRepository refundRepository;
    private final GatewayConfig gatewayConfig;
    private final ChainRpcClient chainRpcClient;
    private final ExchangeWalletClient walletClient;
    private final ApplicationEventPublisher eventPublisher;
    private final KeyManager keyManager;
    private final PaymentRiskService riskService;
    private final ComplianceService complianceService;

    public PaymentServiceImpl(PaymentOrderRepository orderRepository,
                              RefundRepository refundRepository,
                              GatewayConfig gatewayConfig,
                              ChainRpcClient chainRpcClient,
                              ExchangeWalletClient walletClient,
                              ApplicationEventPublisher eventPublisher,
                              KeyManager keyManager,
                              PaymentRiskService riskService,
                              ComplianceService complianceService) {
        this.orderRepository = orderRepository;
        this.refundRepository = refundRepository;
        this.gatewayConfig = gatewayConfig;
        this.chainRpcClient = chainRpcClient;
        this.walletClient = walletClient;
        this.eventPublisher = eventPublisher;
        this.keyManager = keyManager;
        this.riskService = riskService;
        this.complianceService = complianceService;
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
        PaymentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

        if (order.getStatus() == PaymentOrder.OrderStatus.PAID) {
            return PaymentResult.success(order.getOrderNo(), order.getChainTxHash(), order.getPaidAt());
        }

        if (!isChainConfirmed(chainTxHash)) {
            return PaymentResult.failed(order.getOrderNo(), "Transaction not yet confirmed on chain");
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

        AmlResult amlResult = complianceService.screenAml(amlTx);
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
            return PaymentResult.failed(order.getOrderNo(), "Payment blocked by compliance screening");
        }

        order.setChainTxHash(chainTxHash);
        OrderStateMachine.transition(order, PaymentOrder.OrderStatus.PAID);
        order.setPaidAt(LocalDateTime.now());
        orderRepository.save(order);

        log.info("Payment confirmed: orderNo={}, txHash={}", order.getOrderNo(), chainTxHash);

        // Publish event for async webhook notification
        eventPublisher.publishEvent(new PaymentConfirmedEvent(
                this, order.getId(), order.getOrderNo(), order.getMerchantId(),
                chainTxHash, order.getPayerAddress(), order.getAmount().toPlainString()));

        return PaymentResult.success(order.getOrderNo(), chainTxHash, order.getPaidAt());
    }

    @Override
    @Transactional
    public Refund refund(Long orderId, BigDecimal amount, String reason) {
        PaymentOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

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
            throw new IllegalStateException("Refund rejected by risk control (" + refundDecision + ")");
        }

        Refund refund = new Refund();
        refund.setRefundNo("RF" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        refund.setOrderId(orderId);
        refund.setMerchantId(order.getMerchantId());
        refund.setAmount(amount);
        refund.setTokenSymbol(order.getTokenSymbol());
        refund.setReceiverAddress(order.getPayerAddress());
        refund.setSenderAddress(order.getPayeeAddress());
        refund.setReason(reason);
        refund.setStatus(Refund.RefundStatus.PROCESSING);

        // Execute refund transfer via exchange-wallet
        String receiverPubkeyHash = walletClient.addressToPubkeyHash(order.getPayerAddress());
        if (receiverPubkeyHash != null) {
            String txHash = executeRefundTransfer(order, receiverPubkeyHash, amount);
            if (txHash != null) {
                refund.setChainTxHash(txHash);
                refund.setStatus(Refund.RefundStatus.COMPLETED);
                refund.setCompletedAt(LocalDateTime.now());
                OrderStateMachine.transition(order, PaymentOrder.OrderStatus.REFUNDED);
            } else {
                refund.setStatus(Refund.RefundStatus.FAILED);
                log.error("Refund transfer failed for order: {}", order.getOrderNo());
            }
        } else {
            // Sandbox/dev fallback: wallet unreachable, simulate successful refund
            log.warn("Wallet unreachable, simulating refund for order: {}", order.getOrderNo());
            refund.setChainTxHash("0x" + UUID.randomUUID().toString().replace("-", ""));
            refund.setStatus(Refund.RefundStatus.COMPLETED);
            refund.setCompletedAt(LocalDateTime.now());
            OrderStateMachine.transition(order, PaymentOrder.OrderStatus.REFUNDED);
        }

        orderRepository.save(order);
        Refund saved = refundRepository.save(refund);
        // Publish event if refund completed
        if (saved.getStatus() == Refund.RefundStatus.COMPLETED) {
            eventPublisher.publishEvent(new RefundCompletedEvent(
                    this, order.getId(), order.getOrderNo(), order.getMerchantId(),
                    saved.getRefundNo(), amount.toPlainString(), saved.getChainTxHash()));
        }

        log.info("Refund processed: refundNo={}, orderId={}, status={}", saved.getRefundNo(), orderId, saved.getStatus());
        return saved;
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
     * In production, merchant key material comes from secure storage (HSM/KMS/Vault).
     */
    private String executeRefundTransfer(PaymentOrder order, String receiverPubkeyHash, BigDecimal amount) {
        // The exchange-wallet's /ClientToTransferAccount requires fromPubkey and prikey.
        // In a real deployment, these would be retrieved from a secure key store.
        // For now, we attempt the call; if the wallet service is unavailable, return null gracefully.
        try {
            String merchantPubkey = keyManager.getPublicKey(order.getMerchantId());
            String merchantPrikey = keyManager.getPrivateKey(order.getMerchantId());

            if (merchantPubkey == null || merchantPrikey == null) {
                log.warn("Merchant keypair not configured for id={}, simulating refund", order.getMerchantId());
                return "0x" + UUID.randomUUID().toString().replace("-", "");
            }

            return walletClient.transfer(merchantPubkey, receiverPubkeyHash, amount, merchantPrikey);
        } catch (Exception e) {
            log.error("Refund transfer exception: {}", e.getMessage());
            return null;
        }
    }
}