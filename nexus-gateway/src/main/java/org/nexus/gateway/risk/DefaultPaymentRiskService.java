package org.nexus.gateway.risk;

import org.nexus.settlement.risk.RiskEngine;
import org.nexus.settlement.risk.RiskTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Payment risk service that gates payments and refunds.
 *
 * <p>Two-layer evaluation:</p>
 * <ol>
 *   <li><b>Local merchant profile</b> — blacklist short-circuit (FROZEN) and
 *       per-transaction / daily / monthly limit enforcement from the gateway's
 *       {@link RiskProfile} entity (gateway owns merchant-level limits).</li>
 *   <li><b>Delegated rule engine</b> — amount threshold, velocity and blacklist
 *       address rules evaluated by the {@code nexus-settlement}
 *       {@link RiskEngine} (shared risk infrastructure).</li>
 * </ol>
 */
@Service
public class DefaultPaymentRiskService implements PaymentRiskService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPaymentRiskService.class);

    private final RiskProfileRepository riskProfileRepository;
    private final RiskEngine riskEngine;
    private final org.nexus.gateway.repository.PaymentOrderRepository orderRepository;

    public DefaultPaymentRiskService(RiskProfileRepository riskProfileRepository,
                                     RiskEngine riskEngine,
                                     org.nexus.gateway.repository.PaymentOrderRepository orderRepository) {
        this.riskProfileRepository = riskProfileRepository;
        this.riskEngine = riskEngine;
        this.orderRepository = orderRepository;
    }

    @Override
    public RiskDecision evaluatePayment(PaymentRequest request) {
        if (request == null || request.getMerchantId() == null) {
            log.warn("Risk evaluation skipped: missing request or merchantId");
            return RiskDecision.APPROVED;
        }

        Long merchantId = request.getMerchantId();
        RiskProfile profile = riskProfileRepository.findByMerchantId(merchantId).orElse(null);

        // Layer 1: merchant profile guards
        if (profile != null) {
            if (Boolean.TRUE.equals(profile.getBlacklisted())) {
                log.warn("Merchant {} is blacklisted, freezing payment", merchantId);
                return RiskDecision.FROZEN;
            }

            BigDecimal amount = request.getAmount();
            if (amount != null) {
                if (profile.getPerTxLimit() != null && amount.compareTo(profile.getPerTxLimit()) > 0) {
                    log.warn("Merchant {} payment {} exceeds per-tx limit {}",
                            merchantId, amount, profile.getPerTxLimit());
                    return RiskDecision.REJECTED;
                }

                if (profile.getDailyLimit() != null) {
                    BigDecimal daySum = orderRepository.sumMerchantAmountSince(
                            merchantId, LocalDateTime.now().minusDays(1));
                    if (daySum.add(amount).compareTo(profile.getDailyLimit()) > 0) {
                        log.warn("Merchant {} daily limit {} exceeded (current={}, requested={})",
                                merchantId, profile.getDailyLimit(), daySum, amount);
                        return RiskDecision.REJECTED;
                    }
                }

                if (profile.getMonthlyLimit() != null) {
                    BigDecimal monthSum = orderRepository.sumMerchantAmountSince(
                            merchantId, LocalDateTime.now().minusDays(30));
                    if (monthSum.add(amount).compareTo(profile.getMonthlyLimit()) > 0) {
                        log.warn("Merchant {} monthly limit {} exceeded (current={}, requested={})",
                                merchantId, profile.getMonthlyLimit(), monthSum, amount);
                        return RiskDecision.REJECTED;
                    }
                }
            }
        }

        // Layer 2: delegate to nexus-settlement RiskEngine rule chain
        RiskTransaction riskTx = new RiskTransaction();
        riskTx.setType("PAYMENT");
        riskTx.setMerchantId(merchantId);
        riskTx.setPayerAddress(request.getPayerAddress());
        riskTx.setAmount(request.getAmount());
        riskTx.setCurrency(request.getTokenSymbol());
        riskTx.setIdempotencyKey(request.getIdempotencyKey());

        org.nexus.settlement.risk.RiskDecision engineDecision = riskEngine.evaluate(riskTx);
        return mapDecision(engineDecision);
    }

    @Override
    public RiskDecision evaluateRefund(RefundRequest request) {
        if (request == null || request.getMerchantId() == null) {
            return RiskDecision.APPROVED;
        }

        Long merchantId = request.getMerchantId();
        RiskProfile profile = riskProfileRepository.findByMerchantId(merchantId).orElse(null);
        if (profile != null && Boolean.TRUE.equals(profile.getBlacklisted())) {
            log.warn("Merchant {} is blacklisted, freezing refund for order {}",
                    merchantId, request.getOrderId());
            return RiskDecision.FROZEN;
        }

        RiskTransaction riskTx = new RiskTransaction();
        riskTx.setType("REFUND");
        riskTx.setMerchantId(merchantId);
        riskTx.setPayeeAddress(request.getReceiverAddress());
        riskTx.setAmount(request.getAmount());

        org.nexus.settlement.risk.RiskDecision engineDecision = riskEngine.evaluate(riskTx);
        return mapDecision(engineDecision);
    }

    @Override
    public RiskProfile getRiskProfile(Long merchantId) {
        if (Objects.isNull(merchantId)) {
            return null;
        }
        return riskProfileRepository.findByMerchantId(merchantId).orElse(null);
    }

    private RiskDecision mapDecision(org.nexus.settlement.risk.RiskDecision decision) {
        if (decision == null) {
            return RiskDecision.APPROVED;
        }
        return switch (decision) {
            case APPROVED -> RiskDecision.APPROVED;
            case REJECTED -> RiskDecision.REJECTED;
            case PENDING_REVIEW -> RiskDecision.PENDING_REVIEW;
            case FROZEN -> RiskDecision.FROZEN;
        };
    }
}
