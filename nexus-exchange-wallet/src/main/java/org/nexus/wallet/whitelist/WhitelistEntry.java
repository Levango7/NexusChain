package org.nexus.wallet.whitelist;

import java.time.LocalDateTime;

/**
 * Whitelist entry representing an approved withdrawal address for a merchant.
 *
 * <p>When an address is first added to the whitelist, a configurable delay
 * (e.g. 24 hours) must elapse before withdrawals to that address are permitted.
 * The {@link #firstWithdrawalAvailableAt} field records when the delay expires.</p>
 */
public class WhitelistEntry {

    /** Whitelisted wallet address. */
    private String address;

    /** Human-readable label for the address (e.g. "Exchange hot wallet"). */
    private String label;

    /** Merchant ID that owns this whitelist entry. */
    private String merchantId;

    /** Timestamp when the entry was added. */
    private LocalDateTime addedAt;

    /** Timestamp when the first-time withdrawal delay expires. */
    private LocalDateTime firstWithdrawalAvailableAt;

    /** Whether the entry is currently active. */
    private Boolean active = true;

    public WhitelistEntry() {}

    public WhitelistEntry(String address, String label, String merchantId) {
        this.address = address;
        this.label = label;
        this.merchantId = merchantId;
        this.addedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }

    public LocalDateTime getFirstWithdrawalAvailableAt() { return firstWithdrawalAvailableAt; }
    public void setFirstWithdrawalAvailableAt(LocalDateTime firstWithdrawalAvailableAt) {
        this.firstWithdrawalAvailableAt = firstWithdrawalAvailableAt;
    }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
}