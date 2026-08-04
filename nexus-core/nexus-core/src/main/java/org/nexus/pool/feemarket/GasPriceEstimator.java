package org.nexus.pool.feemarket;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 基于历史区块的手续费估算器骨架实现。
 *
 * <p>参考最近若干区块的 gas 使用率与基础费，估算下一区块
 * 的最优 gas 价格。当前为骨架实现。</p>
 *
 * @since 1.2
 */
@Component
public class GasPriceEstimator implements FeeMarket {

    @Override
    public BigDecimal getOptimalGasPrice() {
        // TODO: 取最近 N 个区块的 baseFee 与利用率，按 EIP-1559 公式估算
        return BigDecimal.ZERO;
    }

    @Override
    public BigDecimal estimateGasPrice(TransactionUrgency urgency) {
        // TODO: 按 urgency 映射到 priorityFee 加成系数
        return BigDecimal.ZERO;
    }

    @Override
    public void prioritizeByFee() {
        // TODO: 对交易池中交易按 (priorityFee + baseFee) 降序排序
    }
}