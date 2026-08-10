package org.nexus.gateway.risk;

/**
 * Payment risk service interface for evaluating payments and refunds against
 * risk rules and merchant risk profiles.
 *
 * <p>Implementations typically delegate to the {@code nexus-settlement} module's
 * {@code RiskEngine} for rule evaluation, with local enforcement of merchant
 * limits and blacklist status.</p>
 */
public interface PaymentRiskService {

    /**
     * Evaluate a payment request against risk rules.
     *
     * @param request payment request inputs
     * @return risk decision (APPROVED, REJECTED, PENDING_REVIEW, or FROZEN)
     */
    RiskDecision evaluatePayment(PaymentRequest request);

    /**
     * Evaluate a refund request against risk rules.
     *
     * @param request refund request inputs
     * @return risk decision (APPROVED, REJECTED, PENDING_REVIEW, or FROZEN)
     */
    RiskDecision evaluateRefund(RefundRequest request);

    /**
     * Get the risk profile for a merchant.
     *
     * @param merchantId merchant ID
     * @return the merchant's risk profile, or {@code null} if not configured
     */
    RiskProfile getRiskProfile(Long merchantId);
}