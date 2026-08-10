package org.nexus.gateway;

import org.nexus.gateway.dto.PaymentResult;
import org.nexus.gateway.model.Refund;

import java.math.BigDecimal;

/**
 * Payment service interface covering payment initiation, confirmation, and refunds.
 *
 * <p>Payment operations delegate on-chain transfer construction and signing to
 * the {@code nexus-exchange-wallet} module, and chain interaction to the
 * {@code nexus-sdk}.</p>
 */
public interface PaymentService {

    /**
     * Initiate a payment for an existing order.
     *
     * <p>This transitions the order to {@link org.nexus.gateway.model.PaymentOrder.OrderStatus#PAYING}
     * and returns a checkout URL for the cashier redirect flow.</p>
     *
     * @param orderId     order ID
     * @param payerAddress payer's wallet address
     * @return payment result with checkout URL and pending status
     */
    PaymentResult initiatePayment(Long orderId, String payerAddress);

    /**
     * Confirm a payment after receiving a chain event or manual verification.
     *
     * <p>This verifies the on-chain transaction has reached the required number
     * of confirmations, then transitions the order to
     * {@link org.nexus.gateway.model.PaymentOrder.OrderStatus#PAID}.</p>
     *
     * @param orderId    order ID
     * @param chainTxHash on-chain transaction hash
     * @return payment result with success or failure status
     */
    PaymentResult confirmPayment(Long orderId, String chainTxHash);

    /**
     * Initiate a refund for a paid order.
     *
     * <p>The refund amount must not exceed the original order amount. The refund
     * is executed as an on-chain transfer back to the payer via the
     * {@code nexus-exchange-wallet} signing pipeline.</p>
     *
     * @param orderId order ID
     * @param amount  refund amount
     * @param reason  optional refund reason
     * @return the created refund entity
     */
    Refund refund(Long orderId, BigDecimal amount, String reason);

    /**
     * Query the on-chain status of a payment transaction via nexus-sdk.
     *
     * @param chainTxHash on-chain transaction hash
     * @return {@code true} if the transaction is confirmed with sufficient block depth
     */
    boolean isChainConfirmed(String chainTxHash);
}
