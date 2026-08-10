package org.nexus.bridge.liquidity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultLiquidityManager} 单元测试：验证流动性注入/抽取、
 * 利用率计算与跨链再平衡。
 */
class DefaultLiquidityManagerTest {

    private DefaultLiquidityManager manager;

    @BeforeEach
    void setUp() {
        manager = new DefaultLiquidityManager();
    }

    @Test
    void getLiquidity_lazyCreatesEmptyPool() {
        LiquidityPool pool = manager.getLiquidity("chain-a");
        assertNotNull(pool);
        assertEquals("chain-a", pool.getChainId());
        assertEquals(0, BigDecimal.ZERO.compareTo(pool.getReserve()));
        assertEquals(0.0, pool.getUtilization(), 0.0001);
    }

    @Test
    void getLiquidity_nullChainReturnsNull() {
        assertNull(manager.getLiquidity(null));
        assertNull(manager.getLiquidity(""));
    }

    @Test
    void addLiquidity_increasesReserve() {
        manager.addLiquidity("chain-a", new BigDecimal("1000"));

        LiquidityPool pool = manager.getLiquidity("chain-a");
        assertEquals(0, new BigDecimal("1000").compareTo(pool.getReserve()));
        // 全额注入，储备=基准，利用率应为 0
        assertEquals(0.0, pool.getUtilization(), 0.0001);
    }

    @Test
    void addLiquidity_nonPositiveRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> manager.addLiquidity("chain-a", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> manager.addLiquidity("chain-a", new BigDecimal("-5")));
    }

    @Test
    void removeLiquidity_decreasesReserveAndRaisesUtilization() {
        manager.addLiquidity("chain-a", new BigDecimal("1000"));
        manager.removeLiquidity("chain-a", new BigDecimal("400"));

        LiquidityPool pool = manager.getLiquidity("chain-a");
        assertEquals(0, new BigDecimal("600").compareTo(pool.getReserve()));
        // 储备 600/基准 1000 → 利用率 = 1 - 0.6 = 0.4
        assertEquals(0.4, pool.getUtilization(), 0.0001);
    }

    @Test
    void removeLiquidity_insufficientReserveThrows() {
        manager.addLiquidity("chain-a", new BigDecimal("100"));

        assertThrows(IllegalStateException.class,
                () -> manager.removeLiquidity("chain-a", new BigDecimal("200")));
    }

    @Test
    void rebalance_singlePoolSkipped() {
        manager.addLiquidity("chain-a", new BigDecimal("1000"));
        // 只有一个池，应安静跳过不抛异常
        assertDoesNotThrow(() -> manager.rebalance());
    }

    @Test
    void rebalance_movesFromHighToLowUtilization() {
        // chain-a：注入 1000 后抽走 900 → 利用率 0.9（高）
        manager.addLiquidity("chain-a", new BigDecimal("1000"));
        manager.removeLiquidity("chain-a", new BigDecimal("900"));
        // chain-b：注入 1000 不抽取 → 利用率 0.0（低）
        manager.addLiquidity("chain-b", new BigDecimal("1000"));

        BigDecimal donorReserveBefore = manager.getLiquidity("chain-a").getReserve();
        BigDecimal receiverReserveBefore = manager.getLiquidity("chain-b").getReserve();

        manager.rebalance();

        // 再平衡后，donor 储备减少、receiver 储备增加（调拨量>0）
        assertTrue(manager.getLiquidity("chain-a").getReserve().compareTo(donorReserveBefore) < 0,
                "donor reserve should decrease");
        assertTrue(manager.getLiquidity("chain-b").getReserve().compareTo(receiverReserveBefore) > 0,
                "receiver reserve should increase");
    }

    @Test
    void rebalance_smallGapSkipped() {
        // 两个池利用率接近（差 < 10%），应跳过不调拨
        manager.addLiquidity("chain-a", new BigDecimal("1000"));
        manager.removeLiquidity("chain-a", new BigDecimal("400")); // 利用率 0.4
        manager.addLiquidity("chain-b", new BigDecimal("1000"));
        manager.removeLiquidity("chain-b", new BigDecimal("450")); // 利用率 0.45

        BigDecimal aBefore = manager.getLiquidity("chain-a").getReserve();
        BigDecimal bBefore = manager.getLiquidity("chain-b").getReserve();

        manager.rebalance();

        assertEquals(0, aBefore.compareTo(manager.getLiquidity("chain-a").getReserve()),
                "small gap should not trigger transfer");
        assertEquals(0, bBefore.compareTo(manager.getLiquidity("chain-b").getReserve()));
    }
}
