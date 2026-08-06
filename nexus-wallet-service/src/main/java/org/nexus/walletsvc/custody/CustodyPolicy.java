package org.nexus.walletsvc.custody;

import org.nexus.sdk.wallet.WalletTier;

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
 *
 * <p>迁移历史：原位于 {@code org.nexus.wallet.wallet.custody.CustodyPolicy}
 * （nexus-exchange-wallet），在 Phase 2 微服务化中迁移至 nexus-wallet-service
 * （新包 {@code org.nexus.walletsvc.custody}）。{@link WalletTier} 已迁至
 * nexus-sdk 共享层（{@code org.nexus.sdk.wallet.WalletTier}）。</p>
 */
public class CustodyPolicy {

    /** Maximum balance allowed in the hot wallet. */
    private BigDecimal hotWalletCap;

    /** Maximum balance allowed in the warm wallet. */
    private BigDecimal warmWalletCap;

    /** Maximum balance allowed in the cold wallet. */
    private BigDecimal coldWalletCap;

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

    public BigDecimal getColdWalletCap() { return coldWalletCap; }
    public void setColdWalletCap(BigDecimal coldWalletCap) { this.coldWalletCap = coldWalletCap; }

    public BigDecimal getAutoSweepThreshold() { return autoSweepThreshold; }
    public void setAutoSweepThreshold(BigDecimal autoSweepThreshold) { this.autoSweepThreshold = autoSweepThreshold; }

    public WalletTier getSweepTarget() { return sweepTarget; }
    public void setSweepTarget(WalletTier sweepTarget) { this.sweepTarget = sweepTarget; }

    public BigDecimal getHotWalletFloor() { return hotWalletFloor; }
    public void setHotWalletFloor(BigDecimal hotWalletFloor) { this.hotWalletFloor = hotWalletFloor; }
}