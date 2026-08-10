package org.nexus.bridge.liquidity;

import java.math.BigDecimal;

/**
 * 流动性池实体。
 *
 * <p>描述某条链上某资产在桥侧的储备、利用率与滑点参数。</p>
 *
 * @since 1.2
 */
public class LiquidityPool {

    /** 链 ID */
    private String chainId;

    /** 资产标识（如 NEX / USDC） */
    private String asset;

    /** 当前储备量 */
    private BigDecimal reserve;

    /** 当前利用率（0~1） */
    private double utilization;

    /** 滑点参数（影响大额跨链的滑点曲线） */
    private double slippageParameter;

    public LiquidityPool() {
    }

    public LiquidityPool(String chainId, String asset, BigDecimal reserve,
                         double utilization, double slippageParameter) {
        this.chainId = chainId;
        this.asset = asset;
        this.reserve = reserve;
        this.utilization = utilization;
        this.slippageParameter = slippageParameter;
    }

    public String getChainId() {
        return chainId;
    }

    public void setChainId(String chainId) {
        this.chainId = chainId;
    }

    public String getAsset() {
        return asset;
    }

    public void setAsset(String asset) {
        this.asset = asset;
    }

    public BigDecimal getReserve() {
        return reserve;
    }

    public void setReserve(BigDecimal reserve) {
        this.reserve = reserve;
    }

    public double getUtilization() {
        return utilization;
    }

    public void setUtilization(double utilization) {
        this.utilization = utilization;
    }

    public double getSlippageParameter() {
        return slippageParameter;
    }

    public void setSlippageParameter(double slippageParameter) {
        this.slippageParameter = slippageParameter;
    }
}