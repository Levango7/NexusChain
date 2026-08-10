package org.nexus.pool.feemarket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 基于历史区块的手续费估算器（EIP-1559 风格基本实现）。
 *
 * <p>参考最近若干区块的 gas 使用率与基础费，估算下一区块的最优 gas 价格。
 * 本实现为 EIP-1559 风格的简化版本：</p>
 * <ul>
 *   <li>{@link #getOptimalGasPrice()} = baseFee + priorityFee（priorityFee 取 maxPriorityFee 的一半）</li>
 *   <li>{@link #estimateGasPrice(TransactionUrgency)} = optimalGasPrice × urgency 加成系数</li>
 *   <li>{@link #prioritizeByFee()} 当前为日志占位，待接入交易池引用后实现按 (priorityFee + baseFee) 降序排序</li>
 * </ul>
 *
 * <p>配置通过 {@link FeeMarketConfig} 注入；若未提供则使用内置默认值
 * （baseFee = 1 Gwei，maxPriorityFee = 2 Gwei）。</p>
 *
 * @since 1.2
 */
@Component
public class GasPriceEstimator implements FeeMarket {

    private static final Logger logger = LoggerFactory.getLogger(GasPriceEstimator.class);

    /** 默认基础费：1 Gwei = 10^9 wei */
    private static final BigDecimal DEFAULT_BASE_FEE = new BigDecimal("1000000000");
    /** 默认优先费上限：2 Gwei */
    private static final BigDecimal DEFAULT_MAX_PRIORITY_FEE = new BigDecimal("2000000000");
    /** 默认弹性乘数 */
    private static final double DEFAULT_ELASTICITY_MULTIPLIER = 2.0d;
    /** 默认单区块 gas 上限 */
    private static final long DEFAULT_BLOCK_GAS_LIMIT = 30_000_000L;
    /** 默认目标区块利用率 */
    private static final double DEFAULT_TARGET_UTILIZATION = 0.5d;

    /** LOW 优先级加成系数 */
    private static final double MULTIPLIER_LOW = 1.0d;
    /** NORMAL 优先级加成系数 */
    private static final double MULTIPLIER_NORMAL = 1.2d;
    /** HIGH 优先级加成系数 */
    private static final double MULTIPLIER_HIGH = 1.5d;
    /** URGENT 优先级加成系数 */
    private static final double MULTIPLIER_URGENT = 2.0d;

    private final FeeMarketConfig config;

    /**
     * 默认构造函数：使用内置默认配置。
     *
     * <p>Spring {@code @Component} 装配时使用此构造函数（{@link FeeMarketConfig}
     * 非 Spring bean，无法自动注入）。</p>
     */
    public GasPriceEstimator() {
        this(defaultConfig());
    }

    /**
     * 显式配置构造函数：供测试或运行时定制使用。
     *
     * @param config 手续费市场配置
     */
    public GasPriceEstimator(FeeMarketConfig config) {
        this.config = config;
        logger.info("GasPriceEstimator initialized: baseFee={}, maxPriorityFee={}, elasticityMultiplier={}",
                config.getBaseFee(), config.getMaxPriorityFee(), config.getElasticityMultiplier());
    }

    /**
     * 获取当前最优 gas 价格。
     *
     * <p>EIP-1559 风格：optimalGasPrice = baseFee + priorityFee，
     * 其中 priorityFee 取 {@code maxPriorityFee / 2}（保守策略，避免触及上限）。</p>
     *
     * @return 最优 gas 价格（baseFee + maxPriorityFee/2）
     */
    @Override
    public BigDecimal getOptimalGasPrice() {
        BigDecimal baseFee = config.getBaseFee();
        BigDecimal maxPriorityFee = config.getMaxPriorityFee();
        // priorityFee = maxPriorityFee / 2，向上取整避免零值
        BigDecimal priorityFee = maxPriorityFee.divide(
                new BigDecimal("2"), 0, RoundingMode.CEILING);
        BigDecimal optimal = baseFee.add(priorityFee);
        logger.debug("getOptimalGasPrice: baseFee={}, priorityFee={}, optimal={}",
                baseFee, priorityFee, optimal);
        return optimal;
    }

    /**
     * 按紧急程度估算 gas 价格。
     *
     * <p>在 {@link #getOptimalGasPrice()} 基础上按 urgency 加成：</p>
     * <ul>
     *   <li>{@link TransactionUrgency#LOW} = 1.0×</li>
     *   <li>{@link TransactionUrgency#NORMAL} = 1.2×</li>
     *   <li>{@link TransactionUrgency#HIGH} = 1.5×</li>
     *   <li>{@link TransactionUrgency#URGENT} = 2.0×</li>
     * </ul>
     *
     * @param urgency 紧急程度
     * @return 估算的 gas 价格（optimalGasPrice × 加成系数）
     */
    @Override
    public BigDecimal estimateGasPrice(TransactionUrgency urgency) {
        if (urgency == null) {
            logger.warn("estimateGasPrice called with null urgency, falling back to NORMAL");
            urgency = TransactionUrgency.NORMAL;
        }
        double multiplier;
        switch (urgency) {
            case LOW:
                multiplier = MULTIPLIER_LOW;
                break;
            case NORMAL:
                multiplier = MULTIPLIER_NORMAL;
                break;
            case HIGH:
                multiplier = MULTIPLIER_HIGH;
                break;
            case URGENT:
                multiplier = MULTIPLIER_URGENT;
                break;
            default:
                multiplier = MULTIPLIER_NORMAL;
                break;
        }
        BigDecimal optimal = getOptimalGasPrice();
        // optimal × multiplier，向上取整到整数 wei
        BigDecimal estimated = optimal.multiply(BigDecimal.valueOf(multiplier))
                .setScale(0, RoundingMode.CEILING);
        logger.debug("estimateGasPrice: urgency={}, multiplier={}, estimated={}",
                urgency, multiplier, estimated);
        return estimated;
    }

    /**
     * 按手续费对交易池中交易重新排序，使高费用交易优先打包。
     *
     * <p>当前为占位实现：本估算器未持有交易池引用，无法直接排序。
     * 待后续接入交易池（如 {@code PendingTransPool} / {@code AdoptTransPool}）后，
     * 将按 {@code (priorityFee + baseFee)} 降序对池中交易重排。</p>
     */
    @Override
    public void prioritizeByFee() {
        // No-op: transaction pool reference not yet wired (see javadoc above)
        logger.debug("prioritizeByFee: no-op (transaction pool reference not yet wired); "
                + "pending integration with PendingTransPool/AdoptTransPool");
    }

    /**
     * 构造默认配置（当未显式注入 {@link FeeMarketConfig} 时使用）。
     *
     * @return 默认配置实例
     */
    private static FeeMarketConfig defaultConfig() {
        FeeMarketConfig cfg = new FeeMarketConfig();
        cfg.setBaseFee(DEFAULT_BASE_FEE);
        cfg.setMaxPriorityFee(DEFAULT_MAX_PRIORITY_FEE);
        cfg.setElasticityMultiplier(DEFAULT_ELASTICITY_MULTIPLIER);
        cfg.setBlockGasLimit(DEFAULT_BLOCK_GAS_LIMIT);
        cfg.setTargetBlockUtilization(DEFAULT_TARGET_UTILIZATION);
        return cfg;
    }
}
