package org.nexus.consensus.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StakingServiceImpl} 质押服务测试。
 *
 * <p>覆盖质押、解质押、提取、奖励分发等核心逻辑。
 * 使用真实的 {@link ValidatorRegistry} 作为依赖。</p>
 */
public class StakingServiceImplTest {

    private ValidatorRegistry registry;
    private StakingServiceImpl staking;

    @BeforeEach
    public void setUp() {
        registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        staking = new StakingServiceImpl(60L, new BigDecimal("0.05"));
        injectField(staking, "validatorRegistry", registry);
    }

    private static void injectField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testStakeSuccess() {
        registry.register("addr1", "pub1", new BigDecimal("1000"), 0.05);
        staking.stake("addr1", new BigDecimal("500"));
        assertEquals(new BigDecimal("500"), staking.getStake("addr1"));
    }

    @Test
    public void testStakeAccumulates() {
        staking.stake("addr1", new BigDecimal("500"));
        staking.stake("addr1", new BigDecimal("300"));
        assertEquals(new BigDecimal("800"), staking.getStake("addr1"));
    }

    @Test
    public void testStakeInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> staking.stake(null, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> staking.stake("addr1", null));
        assertThrows(IllegalArgumentException.class, () -> staking.stake("addr1", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> staking.stake("addr1", new BigDecimal("-100")));
    }

    @Test
    public void testUnstakeSuccess() {
        staking.stake("addr1", new BigDecimal("1000"));
        staking.unstake("addr1", new BigDecimal("400"));
        assertEquals(new BigDecimal("600"), staking.getStake("addr1"));
    }

    @Test
    public void testUnstakeExceedingBalance() {
        staking.stake("addr1", new BigDecimal("500"));
        assertThrows(IllegalArgumentException.class,
                () -> staking.unstake("addr1", new BigDecimal("600")));
    }

    @Test
    public void testUnstakeInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> staking.unstake(null, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> staking.unstake("addr1", null));
        assertThrows(IllegalArgumentException.class, () -> staking.unstake("addr1", BigDecimal.ZERO));
    }

    @Test
    public void testGetStakeNonExistent() {
        assertEquals(BigDecimal.ZERO, staking.getStake("nonexistent"));
    }

    @Test
    public void testWithdrawEmptyQueue() {
        assertEquals(BigDecimal.ZERO, staking.withdraw("addr1"));
    }

    @Test
    public void testGetWithdrawableEmpty() {
        assertEquals(BigDecimal.ZERO, staking.getWithdrawable("addr1"));
    }

    @Test
    public void testDistributeRewardsNoActiveValidators() {
        staking.distributeRewards();
    }

    @Test
    public void testDistributeRewardsWithActiveValidators() {
        registry.register("addr1", "pub1", new BigDecimal("1000"), 0.05);
        staking.stake("addr1", new BigDecimal("1000"));
        BigDecimal stakeBefore = staking.getStake("addr1");
        staking.distributeRewards();
        BigDecimal stakeAfter = staking.getStake("addr1");
        assertTrue(stakeAfter.compareTo(stakeBefore) > 0, "奖励应使质押增加");
    }

    @Test
    public void testGetters() {
        assertEquals(60L, staking.getLockPeriodSeconds());
        assertEquals(new BigDecimal("0.05"), staking.getAnnualRewardRate());
    }

    @Test
    public void testDefaultConstructor() {
        StakingServiceImpl def = new StakingServiceImpl();
        assertEquals(7L * 24 * 3600, def.getLockPeriodSeconds());
        assertEquals(new BigDecimal("0.05"), def.getAnnualRewardRate());
    }

    @Test
    public void testLoadSnapshotWithoutPersister() {
        try {
            java.lang.reflect.Method m = StakingServiceImpl.class.getDeclaredMethod("loadSnapshot");
            m.setAccessible(true);
            m.invoke(staking);
        } catch (Exception e) {
            fail("loadSnapshot should not throw: " + e.getMessage());
        }
    }

    @Test
    public void testSaveSnapshotWithoutPersister() {
        try {
            java.lang.reflect.Method m = StakingServiceImpl.class.getDeclaredMethod("saveSnapshot");
            m.setAccessible(true);
            m.invoke(staking);
        } catch (Exception e) {
            fail("saveSnapshot should not throw: " + e.getMessage());
        }
    }
}
