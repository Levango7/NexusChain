package org.nexus.gateway;

import org.nexus.gateway.model.Subscription;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Subscription service interface covering creation, recurring charges, and cancellation.
 *
 * <p>Subscriptions use the {@code SUBSCRIPTION_AUTH} transaction type to authorize
 * the merchant for periodic NEX deductions from the payer's wallet.</p>
 */
public interface SubscriptionService {

    /**
     * Create a new subscription agreement.
     *
     * <p>This submits a SUBSCRIPTION_AUTH transaction on-chain to authorize the
     * merchant for recurring charges.</p>
     *
     * @param merchantId   merchant ID
     * @param payerAddress  payer wallet address
     * @param payeeAddress  merchant settlement wallet address
     * @param amount        charge amount per cycle
     * @param cycleDays     billing cycle length in days
     * @return the persisted subscription entity
     */
    Subscription createSubscription(Long merchantId, String payerAddress, String payeeAddress,
                                    BigDecimal amount, int cycleDays);

    /**
     * Look up a subscription by ID.
     *
     * @param subscriptionId subscription ID
     * @return the subscription if found
     */
    Optional<Subscription> findById(Long subscriptionId);

    /**
     * Execute a recurring charge for a subscription.
     *
     * <p>This constructs a transfer via the {@code nexus-exchange-wallet} module
     * and updates the subscription's {@code chargedCount} and {@code nextChargeAt}.</p>
     *
     * @param subscriptionId subscription ID
     * @return the on-chain transaction hash, or {@code null} if the charge failed
     */
    String charge(Long subscriptionId);

    /**
     * Cancel an active subscription.
     *
     * <p>This revokes the on-chain authorization and transitions the subscription
     * to {@link org.nexus.gateway.model.Subscription.SubscriptionStatus#CANCELLED}.</p>
     *
     * @param subscriptionId subscription ID
     * @return the updated subscription entity
     */
    Subscription cancel(Long subscriptionId);

    /**
     * Process all subscriptions due for a recurring charge.
     *
     * <p>Called by a scheduled task. Iterates active subscriptions whose
     * {@code nextChargeAt} has passed and attempts to charge each.</p>
     *
     * @return the number of successful charges processed
     */
    int processDueSubscriptions();
}
