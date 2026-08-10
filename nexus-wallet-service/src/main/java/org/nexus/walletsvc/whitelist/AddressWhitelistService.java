package org.nexus.walletsvc.whitelist;

/**
 * Address whitelist service interface for managing approved withdrawal
 * addresses and enforcing the first-time withdrawal delay.
 *
 * <p>Withdrawals to non-whitelisted addresses are blocked or routed through
 * an enhanced approval flow. Newly added addresses are subject to a
 * configurable delay before the first withdrawal is permitted.</p>
 *
 * <p>迁移历史：原位于 {@code org.nexus.wallet.wallet.whitelist.AddressWhitelistService}
 * （nexus-exchange-wallet），在 Phase 2 微服务化中迁移至 nexus-wallet-service
 * （新包 {@code org.nexus.walletsvc.whitelist}）。</p>
 */
public interface AddressWhitelistService {

    /**
     * Add an address to the whitelist for the given merchant.
     *
     * @param address    wallet address to whitelist
     * @param label      human-readable label
     * @param merchantId merchant ID
     * @return the created whitelist entry with firstWithdrawalAvailableAt set
     */
    WhitelistEntry addWhitelist(String address, String label, String merchantId);

    /**
     * Remove an address from the whitelist.
     *
     * @param address wallet address to remove
     */
    void removeWhitelist(String address);

    /**
     * Whether the given address is currently on the whitelist and active.
     *
     * @param address wallet address
     * @return {@code true} if the address is whitelisted and active
     */
    boolean isWhitelisted(String address);

    /**
     * Whether the address is subject to the first-time withdrawal delay.
     *
     * <p>Returns {@code true} if the address is whitelisted but the
     * first-time delay has not yet elapsed (i.e. withdrawals should be
     * blocked or routed through enhanced approval).</p>
     *
     * @param address wallet address
     * @return {@code true} if the first-time withdrawal delay is still in effect
     */
    boolean checkFirstTimeWithdrawal(String address);
}