package org.nexus.wallet.custody;

import java.math.BigDecimal;

/**
 * Custody policy entity defining the limits and auto-sweep thresholds for the
 * hot/warm/cold tiering model.
 *
 * <p>When the hot wallet balance exceeds {@link #hotWalletCap}, the excess is
 * swept to the warm or cold wallet. When the hot balance falls below the
 * operational floor, funds are pulled from the warm wallet. The
 * {@link #autoSweepThreshold} triggers an automatic rebalance toward
 * {@link #sweepTarget}.</p>
 */
public class CustodyPolicy {

    /** Maximum balance allowed in the hot wallet. */
    private BigDecimal hotWalletCap;

    /** Maximum balance allowed in the warm wallet. */
    private BigDecimal warmWalletCap;

    /** When hot balance exceeds this threshold, sweep excess to sweepTarget. */
    private BigDecimal autoSweepThreshold;

    /** Target tier for auto-sweep operations. */
    private WalletTier sweepTarget = WalletTier.COLD;

    /** Minimum operational floor for the hot wallet (triggers pull from warm). */
    private BigDecimal hotWalletFloor;

    public CustodyPolicy() {}

    public CustodyPolicy(BigDecimal hotWalletCap, BigDecimal warmWalletCap, BigDecimal autoSweepThreshold) {
        this.hotWalletCap = hotWalletCap;
        this.warmWalletCap = warmWalletCap;
        this.autoSweepThreshold = autoSweepThreshold;
    }

    // --- Getters and Setters ---

    public BigDecimal getHotWalletCap() { return hotWalletCap; }
    public void setHotWalletCap(BigDecimal hotWalletCap) { this.hotWalletCap = hotWalletCap; }

    public BigDecimal getWarmWalletCap() { return warmWalletCap; }
    public void setWarmWalletCap(BigDecimal warmWalletCap) { this.warmWalletCap = warmWalletCap; }

    public BigDecimal getAutoSweepThreshold() { return autoSweepThreshold; }
    public void setAutoSweepThreshold(BigDecimal autoSweepThreshold) { this.autoSweepThreshold = autoSweepThreshold; }

    public WalletTier getSweepTarget() { return sweepTarget; }
    public void setSweepTarget(WalletTier sweepTarget) { this.sweepTarget = sweepTarget; }

    public BigDecimal getHotWalletFloor() { return hotWalletFloor; }
    public void setHotWalletFloor(BigDecimal hotWalletFloor) { this.hotWalletFloor = hotWalletFloor; }
}