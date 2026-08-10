package org.nexus.pool.feemarket;

import java.math.BigDecimal;

/**
 * 手续费市场接口。
 *
 * <p>定义交易池基于 EIP-1559 风格的费用市场能力：
 * 最优 gas 价格估算、按紧急程度估算、按费用排序交易。</p>
 *
 * @since 1.2
 */
public interface FeeMarket {

    /**
     * 获取当前最优 gas 价格。
     *
     * @return 最优 gas 价格
     */
    BigDecimal getOptimalGasPrice();

    /**
     * 按紧急程度估算 gas 价格。
     *
     * @param urgency 紧急程度
     * @return 估算的 gas 价格
     */
    BigDecimal estimateGasPrice(TransactionUrgency urgency);

    /**
     * 按手续费对交易池中交易重新排序，使高费用交易优先打包。
     */
    void prioritizeByFee();
}