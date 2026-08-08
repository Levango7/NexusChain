package org.nexus.pool.feemarket;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link FeeMarketConfig} 配置实体测试。
 */
public class FeeMarketConfigTest {

    @Test
    public void testDefaultConstructor() {
        FeeMarketConfig cfg = new FeeMarketConfig();
        assertNull(cfg.getBaseFee());
        assertNull(cfg.getMaxPriorityFee());
        assertEquals(0.0d, cfg.getElasticityMultiplier(), 0.0001);
        assertEquals(0L, cfg.getBlockGasLimit());
        assertEquals(0.0d, cfg.getTargetBlockUtilization(), 0.0001);
    }

    @Test
    public void testSetters() {
        FeeMarketConfig cfg = new FeeMarketConfig();
        cfg.setBaseFee(new BigDecimal("1000000000"));
        cfg.setMaxPriorityFee(new BigDecimal("2000000000"));
        cfg.setElasticityMultiplier(2.0);
        cfg.setBlockGasLimit(30_000_000L);
        cfg.setTargetBlockUtilization(0.5);

        assertEquals(new BigDecimal("1000000000"), cfg.getBaseFee());
        assertEquals(new BigDecimal("2000000000"), cfg.getMaxPriorityFee());
        assertEquals(2.0d, cfg.getElasticityMultiplier(), 0.0001);
        assertEquals(30_000_000L, cfg.getBlockGasLimit());
        assertEquals(0.5d, cfg.getTargetBlockUtilization(), 0.0001);
    }
}