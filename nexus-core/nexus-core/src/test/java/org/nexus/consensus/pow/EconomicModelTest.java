package org.nexus.consensus.pow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link EconomicModel} 单元测试。
 *
 * <p>通过反射注入 {@code @Value} 字段，避免依赖 Spring 容器。</p>
 */
class EconomicModelTest {

    private EconomicModel model;

    @BeforeEach
    void setUp() throws Exception {
        model = new EconomicModel();
        setField(model, "blockInterval", 30);
        setField(model, "blockIntervalSwitchEra", -1L); // 不切换
        setField(model, "blockIntervalSwitchTo", 10);
        setField(model, "blocksPerEra", 1000);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void constantsAreCorrect() {
        assertEquals(100_000_000L, EconomicModel.NEX);
        assertEquals(20 * 100_000_000L, EconomicModel.INITIAL_SUPPLY);
        assertEquals(1_051_200L * 2, EconomicModel.HALF_PERIOD);
    }

    @Test
    void rewardAtHeightZeroIsInitialSupply() {
        assertEquals(EconomicModel.INITIAL_SUPPLY, model.getConsensusRewardAtHeight(0));
    }

    @Test
    void rewardDecreasesAfterHalvingPeriod() {
        long r0 = model.getConsensusRewardAtHeight(0);
        long r1 = model.getConsensusRewardAtHeight(EconomicModel.HALF_PERIOD);
        // era 1 reward = INITIAL * 0.522
        assertTrue(r1 < r0);
        assertTrue(r1 > 0);
    }

    @Test
    void rewardFurtherDecreasesAfterTwoHalvingPeriods() {
        long r1 = model.getConsensusRewardAtHeight(EconomicModel.HALF_PERIOD);
        long r2 = model.getConsensusRewardAtHeight(EconomicModel.HALF_PERIOD * 2);
        assertTrue(r2 < r1);
    }

    @Test
    void rewardWithinSameEraIsConstant() {
        long r0 = model.getConsensusRewardAtHeight(0);
        long rMid = model.getConsensusRewardAtHeight(EconomicModel.HALF_PERIOD / 2);
        long rEnd = model.getConsensusRewardAtHeight(EconomicModel.HALF_PERIOD - 1);
        assertEquals(r0, rMid);
        assertEquals(r0, rEnd);
    }

    @Test
    void totalSupplyIsPositive() {
        long supply = model.getTotalSupply();
        assertTrue(supply > 0);
        // 约 88 million NEX * 1e8 satoshi
        assertTrue(supply > EconomicModel.NEX);
    }

    @Test
    void totalSupplyNexIsHumanReadable() {
        double nex = model.getTotalSupplyNex();
        assertTrue(nex > 1_000_000); // 至少百万级
        assertTrue(nex < 1_000_000_000); // 小于 10 亿
    }

    @Test
    void printRewardPerEraRuns() {
        // 仅验证不抛异常
        EconomicModel.printRewardPerEra();
    }

    @Test
    void rewardWithIntervalSwitch() throws Exception {
        setField(model, "blockIntervalSwitchEra", 0L); // 从 era 0 切换
        long r = model.getConsensusRewardAtHeight(0);
        // reward * switchTo / blockInterval = INITIAL * 10 / 30
        long expected = EconomicModel.INITIAL_SUPPLY * 10 / 30;
        assertEquals(expected, r);
    }
}