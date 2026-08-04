package org.nexus.gateway.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default skeleton implementation of {@link PaymentRiskService}.
 *
 * <p>All methods are stubbed and log a TODO marker. Production wiring should
 * delegate rule evaluation to the {@code nexus-settlement} module's
 * {@code RiskEngine} and enforce per-merchant limits and blacklist status
 * from the local {@link RiskProfile}.</p>
 */
@Service
public class DefaultPaymentRiskService implements PaymentRiskService {

    private static final Logger log = LoggerFactory.getLogger(DefaultPaymentRiskService.class);

    @Override
    public RiskDecision evaluatePayment(PaymentRequest request) {
        // TODO: load RiskProfile for request.getMerchantId()
        // TODO: short-circuit FROZEN if profile.blacklisted
        // TODO: enforce perTxLimit / dailyLimit / monthlyLimit
        // TODO: delegate to nexus-settlement RiskEngine for rule evaluation
        // TODO: return PENDING_REVIEW when score is in the manual-review band
        log.warn("evaluatePayment not implemented: merchantId={}, amount={}",
                request.getMerchantId(), request.getAmount());
        return RiskDecision.APPROVED;
    }

    @Override
    public RiskDecision evaluateRefund(RefundRequest request) {
        // TODO: load RiskProfile for request.getMerchantId()
        // TODO: detect abnormal refund patterns (high frequency, large amount)
        // TODO: delegate to nexus-settlement RiskEngine for refund-specific rules
        log.warn("evaluateRefund not implemented: orderId={}, amount={}",
                request.getOrderId(), request.getAmount());
        return RiskDecision.APPROVED;
    }

    @Override
    public RiskProfile getRiskProfile(Long merchantId) {
        // TODO: load RiskProfile by merchantId from repository
        log.warn("getRiskProfile not implemented: merchantId={}", merchantId);
        return null;
    }
}