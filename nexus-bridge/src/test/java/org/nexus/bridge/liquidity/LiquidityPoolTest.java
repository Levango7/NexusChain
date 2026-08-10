package org.nexus.bridge.liquidity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LiquidityPool} 实体单元测试：覆盖构造与字段读写。
 */
class LiquidityPoolTest {

    @Test
    @DisplayName("默认构造产生空对象")
    void defaultConstructor_emptyObject() {
        LiquidityPool pool = new LiquidityPool();
        assertNull(pool.getChainId());
        assertNull(pool.getAsset());
        assertNull(pool.getReserve());
        assertEquals(0.0, pool.getUtilization());
        assertEquals(0.0, pool.getSlippageParameter());
    }

    @Test
    @DisplayName("全参数构造应正确设置所有字段")
    void fullConstructor_setsAllFields() {
        LiquidityPool pool = new LiquidityPool("ethereum", "NEX", new BigDecimal("1000"), 0.5, 0.003);
        assertEquals("ethereum", pool.getChainId());
        assertEquals("NEX", pool.getAsset());
        assertEquals(0, new BigDecimal("1000").compareTo(pool.getReserve()));
        assertEquals(0.5, pool.getUtilization());
        assertEquals(0.003, pool.getSlippageParameter());
    }

    @Test
    @DisplayName("setter/getter 正确往返")
    void settersGetters_roundTrip() {
        LiquidityPool pool = new LiquidityPool();
        pool.setChainId("bsc");
        pool.setAsset("USDC");
        pool.setReserve(new BigDecimal("500"));
        pool.setUtilization(0.8);
        pool.setSlippageParameter(0.005);

        assertEquals("bsc", pool.getChainId());
        assertEquals("USDC", pool.getAsset());
        assertEquals(0, new BigDecimal("500").compareTo(pool.getReserve()));
        assertEquals(0.8, pool.getUtilization());
        assertEquals(0.005, pool.getSlippageParameter());
    }
}