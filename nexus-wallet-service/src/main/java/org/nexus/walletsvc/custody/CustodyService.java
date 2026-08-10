package org.nexus.walletsvc.custody;

import org.nexus.sdk.wallet.WalletTier;

import java.math.BigDecimal;

/**
 * Custody service interface for the hot/warm/cold tiering model.
 *
 * <p>The hot wallet serves immediate withdrawals; the warm wallet holds
 * semi-online funds requiring limited approval to move; the cold wallet is
 * air-gapped and requires full multi-sig approval. The service enforces the
 * {@link CustodyPolicy} caps and performs rebalancing between tiers.</p>
 *
 * <p>迁移历史：原位于 {@code org.nexus.wallet.wallet.custody.CustodyService}
 * （nexus-exchange-wallet），在 Phase 2 微服务化中迁移至 nexus-wallet-service
 * （新包 {@code org.nexus.walletsvc.custody}）。{@link WalletTier} 已迁至
 * nexus-sdk 共享层（{@code org.nexus.sdk.wallet.WalletTier}）。</p>
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

    /**
     * Whether the given wallet is under cold custody (air-gapped storage).
     *
     * @param walletId wallet ID
     * @return {@code true} if the wallet is under cold custody
     */
    boolean isColdCustody(String walletId);

    /**
     * Get the custody tier for the given wallet.
     *
     * @param walletId wallet ID
     * @return custody tier name (e.g. "HOT", "WARM", "COLD")
     */
    String getCustodyTier(String walletId);
}