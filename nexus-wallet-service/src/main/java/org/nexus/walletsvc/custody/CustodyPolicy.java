package org.nexus.walletsvc.custody;

import org.nexus.sdk.wallet.WalletTier;
import org.springframework.beans.factory.InitializingBean;

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
public class CustodyPolicy implements InitializingBean {

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

    /** Cold tier wallet ID 前缀（#17 真实化：替代硬编码 "cold" 前缀猜测）。 */
    private String coldWalletPrefix = "cold";

    /** Warm tier wallet ID 前缀（替代硬编码 "warm" 前缀猜测）。 */
    private String warmWalletPrefix = "warm";

    /**
     * 冷钱包的<b>真实链上地址</b>（自动再平衡的两个方向都用它：sweep 时是转入目标，
     * floor pull 时是转出来源）。
     *
     * <p><b>为什么必须有它（2026-10-26 修复）</b>：{@code rebalance()} 修复前传的是字面量
     * {@code "cold-sweep"} / {@code "hot-floor"}，到
     * {@code WalletUtils.addressToPubkeyHash}（Base58 解码失败返回 null，`WalletUtils:231-234`）
     * 会被判为非法地址 —— 也就是说<b>自动再平衡从来没有真正可用过</b>，
     * 即使有人注册了策略也一样。</p>
     *
     * <p>现在要求显式配置真实地址；未配置时 {@code rebalance()} <b>明确拒绝执行</b>
     * （抛 {@code IllegalStateException}），而不是拿占位符去调链上通道。
     * 配置不完整就该报错，不能假装成功。</p>
     */
    private String coldWalletAddress;

    public CustodyPolicy() {}

    /**
     * 启用托管策略时的启动校验（2026-10-27）。
     *
     * <p><b>为什么放在这里</b>：{@code nexus.custody.enabled=true} 是显式开关，
     * 但若只启用不配阈值，{@code rebalance()} 两个分支都会因阈值 null 直接跳过，
     * 结果是"策略已启用"却什么都不做——正是本项目反复收口的静默成功形态。
     * 因此启用即要求配置完整；缺任何一项都在启动期失败。</p>
     *
     * <p>手动 {@code new CustodyPolicy(...)} 的测试不会触发本方法（非 Spring 管理），
     * 现有单测语义不变。</p>
     */
    @Override
    public void afterPropertiesSet() {
        if (autoSweepThreshold == null && hotWalletFloor == null) {
            throw new IllegalStateException(
                    "nexus.custody.enabled=true but neither auto-sweep-threshold nor "
                            + "hot-wallet-floor is configured; rebalance would silently do nothing");
        }
        if (coldWalletAddress == null || coldWalletAddress.isBlank()) {
            throw new IllegalStateException(
                    "nexus.custody.enabled=true requires a real cold-wallet-address; "
                            + "a placeholder would be rejected by address parsing downstream");
        }
    }

    public CustodyPolicy(BigDecimal hotWalletCap, BigDecimal warmWalletCap, BigDecimal autoSweepThreshold) {
        this.hotWalletCap = hotWalletCap;
        this.warmWalletCap = warmWalletCap;
        this.autoSweepThreshold = autoSweepThreshold;
    }

    public String getColdWalletPrefix() { return coldWalletPrefix; }
    public void setColdWalletPrefix(String coldWalletPrefix) {
        if (coldWalletPrefix != null && !coldWalletPrefix.isEmpty()) {
            this.coldWalletPrefix = coldWalletPrefix.toLowerCase();
        }
    }

    public String getWarmWalletPrefix() { return warmWalletPrefix; }
    public void setWarmWalletPrefix(String warmWalletPrefix) {
        if (warmWalletPrefix != null && !warmWalletPrefix.isEmpty()) {
            this.warmWalletPrefix = warmWalletPrefix.toLowerCase();
        }
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

    public String getColdWalletAddress() { return coldWalletAddress; }
    public void setColdWalletAddress(String coldWalletAddress) {
        this.coldWalletAddress = coldWalletAddress == null ? null : coldWalletAddress.trim();
    }
}