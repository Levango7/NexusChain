package org.nexus.wallet.whitelist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Default skeleton implementation of {@link AddressWhitelistService}.
 *
 * <p>All methods are stubbed and log a TODO marker. Production wiring should
 * persist whitelist entries in the wallet store, enforce the configurable
 * first-time withdrawal delay, and audit all add/remove operations.</p>
 */
@Service
public class DefaultAddressWhitelistService implements AddressWhitelistService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAddressWhitelistService.class);

    @Override
    public WhitelistEntry addWhitelist(String address, String label, String merchantId) {
        // TODO: verify address is a valid chain address
        // TODO: persist a new WhitelistEntry with addedAt = now
        // TODO: set firstWithdrawalAvailableAt = now + configurable delay (e.g. 24h)
        // TODO: emit audit event for the whitelist addition
        log.warn("addWhitelist not implemented: address={}, label={}, merchantId={}", address, label, merchantId);
        WhitelistEntry stub = new WhitelistEntry(address, label, merchantId);
        stub.setFirstWithdrawalAvailableAt(LocalDateTime.now().plusHours(24));
        return stub;
    }

    @Override
    public void removeWhitelist(String address) {
        // TODO: mark the WhitelistEntry as inactive (or delete per policy)
        // TODO: emit audit event for the whitelist removal
        log.warn("removeWhitelist not implemented: address={}", address);
    }

    @Override
    public boolean isWhitelisted(String address) {
        // TODO: query the whitelist store for an active entry with this address
        log.warn("isWhitelisted not implemented: address={}", address);
        return false;
    }

    @Override
    public boolean checkFirstTimeWithdrawal(String address) {
        // TODO: load WhitelistEntry; return true if now < firstWithdrawalAvailableAt
        log.warn("checkFirstTimeWithdrawal not implemented: address={}", address);
        return false;
    }
}