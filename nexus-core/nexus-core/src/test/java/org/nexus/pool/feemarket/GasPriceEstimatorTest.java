package org.nexus.pool.feemarket;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GasPriceEstimator} 测试。
 */
public class GasPriceEstimatorTest {

    @Test
    public void testDefaultConstructor() {
        GasPriceEstimator estimator = new GasPriceEstimator();
        // baseFee=1Gwei, maxPriorityFee=2Gwei, optimal = baseFee + maxPriorityFee/2 = 1Gwei + 1Gwei = 2Gwei
        BigDecimal optimal = estimator.getOptimalGasPrice();
        assertEquals(new BigDecimal("2000000000"), optimal);
    }

    @Test
    public void testGetOptimalGasPrice() {
        FeeMarketConfig cfg = new FeeMarketConfig();
        cfg.setBaseFee(new BigDecimal("100"));
        cfg.setMaxPriorityFee(new BigDecimal("10"));
        cfg.setElasticityMultiplier(2.0);
        cfg.setBlockGasLimit(30_000_000L);
        cfg.setTargetBlockUtilization(0.5);

        GasPriceEstimator estimator = new GasPriceEstimator(cfg);
        // priorityFee = 10 / 2 = 5 (CEILING)
        // optimal = 100 + 5 = 105
        assertEquals(new BigDecimal("105"), estimator.getOptimalGasPrice());
    }

    @Test
    public void testGetOptimalGasPriceOddPriorityFee() {
        FeeMarketConfig cfg = new FeeMarketConfig();
        cfg.setBaseFee(new BigDecimal("100"));
        cfg.setMaxPriorityFee(new BigDecimal("11"));
        cfg.setElasticityMultiplier(2.0);
        cfg.setBlockGasLimit(30_000_000L);
        cfg.setTargetBlockUtilization(0.5);

        GasPriceEstimator estimator = new GasPriceEstimator(cfg);
        // priorityFee = 11 / 2 = 6 (CEILING)
        // optimal = 100 + 6 = 106
        assertEquals(new BigDecimal("106"), estimator.getOptimalGasPrice());
    }

    @Test
    public void testEstimateGasPriceLow() {
        GasPriceEstimator estimator = new GasPriceEstimator();
        BigDecimal estimated = estimator.estimateGasPrice(TransactionUrgency.LOW);
        // optimal=2Gwei, multiplier=1.0, estimated=2Gwei
        assertEquals(new BigDecimal("2000000000"), estimated);
    }

    @Test
    public void testEstimateGasPriceNormal() {
        GasPriceEstimator estimator = new GasPriceEstimator();
        BigDecimal estimated = estimator.estimateGasPrice(TransactionUrgency.NORMAL);
        // optimal=2Gwei, multiplier=1.2, estimated=2.4Gwei
        assertEquals(new BigDecimal("2400000000"), estimated);
    }

    @Test
    public void testEstimateGasPriceHigh() {
        GasPriceEstimator estimator = new GasPriceEstimator();
        BigDecimal estimated = estimator.estimateGasPrice(TransactionUrgency.HIGH);
        // optimal=2Gwei, multiplier=1.5, estimated=3Gwei
        assertEquals(new BigDecimal("3000000000"), estimated);
    }

    @Test
    public void testEstimateGasPriceUrgent() {
        GasPriceEstimator estimator = new GasPriceEstimator();
        BigDecimal estimated = estimator.estimateGasPrice(TransactionUrgency.URGENT);
        // optimal=2Gwei, multiplier=2.0, estimated=4Gwei
        assertEquals(new BigDecimal("4000000000"), estimated);
    }

    @Test
    public void testEstimateGasPriceNullUrgency() {
        GasPriceEstimator estimator = new GasPriceEstimator();
        BigDecimal estimated = estimator.estimateGasPrice(null);
        // null falls back to NORMAL: optimal=2Gwei, multiplier=1.2, estimated=2.4Gwei
        assertEquals(new BigDecimal("2400000000"), estimated);
    }

    @Test
    public void testPrioritizeByFee() {
        GasPriceEstimator estimator = new GasPriceEstimator();
        // No-op, should not throw
        estimator.prioritizeByFee();
    }

    @Test
    public void testTransactionUrgencyEnum() {
        TransactionUrgency[] values = TransactionUrgency.values();
        assertEquals(4, values.length);
        assertSame(TransactionUrgency.LOW, TransactionUrgency.valueOf("LOW"));
        assertSame(TransactionUrgency.NORMAL, TransactionUrgency.valueOf("NORMAL"));
        assertSame(TransactionUrgency.HIGH, TransactionUrgency.valueOf("HIGH"));
        assertSame(TransactionUrgency.URGENT, TransactionUrgency.valueOf("URGENT"));
    }
}