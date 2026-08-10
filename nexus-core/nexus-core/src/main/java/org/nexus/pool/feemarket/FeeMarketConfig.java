package org.nexus.pool.feemarket;

import java.math.BigDecimal;

/**
 * 手续费市场配置实体。
 *
 * <p>定义 EIP-1559 风格的基础费、优先费上限与波动参数。</p>
 *
 * @since 1.2
 */
public class FeeMarketConfig {

    /** 基础费（baseFee） */
    private BigDecimal baseFee;

    /** 优先费上限（priorityFee cap） */
    private BigDecimal maxPriorityFee;

    /** 长期波动参数（用于调整 baseFee） */
    private double elasticityMultiplier;

    /** 单区块 gas 上限 */
    private long blockGasLimit;

    /** 目标区块利用率（0~1） */
    private double targetBlockUtilization;

    public FeeMarketConfig() {
    }

    public BigDecimal getBaseFee() {
        return baseFee;
    }

    public void setBaseFee(BigDecimal baseFee) {
        this.baseFee = baseFee;
    }

    public BigDecimal getMaxPriorityFee() {
        return maxPriorityFee;
    }

    public void setMaxPriorityFee(BigDecimal maxPriorityFee) {
        this.maxPriorityFee = maxPriorityFee;
    }

    public double getElasticityMultiplier() {
        return elasticityMultiplier;
    }

    public void setElasticityMultiplier(double elasticityMultiplier) {
        this.elasticityMultiplier = elasticityMultiplier;
    }

    public long getBlockGasLimit() {
        return blockGasLimit;
    }

    public void setBlockGasLimit(long blockGasLimit) {
        this.blockGasLimit = blockGasLimit;
    }

    public double getTargetBlockUtilization() {
        return targetBlockUtilization;
    }

    public void setTargetBlockUtilization(double targetBlockUtilization) {
        this.targetBlockUtilization = targetBlockUtilization;
    }
}