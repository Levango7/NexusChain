package org.nexus.wallet.custody;

import java.math.BigDecimal;

/**
 * Custody service interface for the hot/warm/cold tiering model.
 *
 * <p>The hot wallet serves immediate withdrawals; the warm wallet holds
 * semi-online funds requiring limited approval to move; the cold wallet is
 * air-gapped and requires full multi-sig approval. The service enforces the
 * {@link CustodyPolicy} caps and performs rebalancing between tiers.</p>
 */
public interface CustodyService {

    /**
     * Deposit funds from the hot wallet to the cold wallet.
     *
     * @param address cold wallet address
     * @param amount  amount to deposit
     * @return on-chain transaction hash of the deposit
     */
    String depositToCold(String address, BigDecimal amount);

    /**
     * Withdraw funds from the cold wallet to the hot wallet.
     *
     * @param address     cold wallet address
     * @param amount      amount to withdraw
     * @param approvalId  multi-sig approval ID authorizing the withdrawal
     * @return on-chain transaction hash of the withdrawal
     */
    String withdrawFromCold(String address, BigDecimal amount, String approvalId);

    /**
     * Get the current hot wallet balance.
     *
     * @return hot wallet balance
     */
    BigDecimal getHotBalance();

    /**
     * Get the current cold wallet balance.
     *
     * @return cold wallet balance
     */
    BigDecimal getColdBalance();

    /**
     * Rebalance funds across tiers to comply with the custody policy.
     *
     * @param target target tier to rebalance toward (HOT, WARM, or COLD)
     */
    void rebalance(WalletTier target);
}