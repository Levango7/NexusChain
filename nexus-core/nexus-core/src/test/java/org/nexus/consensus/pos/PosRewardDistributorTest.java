package org.nexus.consensus.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link PosRewardDistributor} 奖励分配器测试。
 */
public class PosRewardDistributorTest {

    private PosRewardDistributor distributor;
    private ValidatorRegistry registry;
    private StakingService staking;

    @BeforeEach
    public void setUp() {
        distributor = new PosRewardDistributor(new BigDecimal("10"));
        registry = mock(ValidatorRegistry.class);
        staking = mock(StakingService.class);
        injectField(distributor, "validatorRegistry", registry);
        injectField(distributor, "stakingService", staking);
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
    public void testDistributeBlockRewardWithFees() {
        BigDecimal reward = distributor.distributeBlockReward("addr1", new BigDecimal("5"));
        assertEquals(new BigDecimal("15"), reward);
        verify(staking).stake("addr1", new BigDecimal("15"));
    }

    @Test
    public void testDistributeBlockRewardNullFees() {
        BigDecimal reward = distributor.distributeBlockReward("addr1", null);
        assertEquals(new BigDecimal("10"), reward);
        verify(staking).stake("addr1", new BigDecimal("10"));
    }

    @Test
    public void testDistributeBlockRewardZeroFees() {
        BigDecimal reward = distributor.distributeBlockReward("addr1", BigDecimal.ZERO);
        assertEquals(new BigDecimal("10"), reward);
    }

    @Test
    public void testDistributeEpochRewardNull() {
        distributor.distributeEpochReward(null);
        verifyNoInteractions(staking);
    }

    @Test
    public void testDistributeEpochRewardZeroOrNegative() {
        distributor.distributeEpochReward(BigDecimal.ZERO);
        distributor.distributeEpochReward(new BigDecimal("-1"));
        verifyNoInteractions(staking);
    }

    @Test
    public void testDistributeEpochRewardNoActiveValidators() {
        when(registry.getActiveValidators()).thenReturn(Collections.emptyList());
        distributor.distributeEpochReward(new BigDecimal("100"));
        verifyNoInteractions(staking);
    }

    @Test
    public void testDistributeEpochRewardWithValidators() {
        Validator v1 = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        Validator v2 = new Validator("addr2", "pub2", new BigDecimal("3000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getActiveValidators()).thenReturn(Arrays.asList(v1, v2));

        distributor.distributeEpochReward(new BigDecimal("100"));
        // 应该向两个验证人都分发奖励
        verify(staking, atLeast(2)).stake(anyString(), any(BigDecimal.class));
    }

    @Test
    public void testDistributeEpochRewardZeroTotalStake() {
        Validator v = new Validator("addr1", "pub1", BigDecimal.ZERO, 0.05, ValidatorStatus.ACTIVE);
        when(registry.getActiveValidators()).thenReturn(Collections.singletonList(v));
        distributor.distributeEpochReward(new BigDecimal("100"));
        verifyNoInteractions(staking);
    }

    @Test
    public void testGetBaseBlockReward() {
        assertEquals(new BigDecimal("10"), distributor.getBaseBlockReward());
    }

    @Test
    public void testDefaultConstructor() {
        PosRewardDistributor def = new PosRewardDistributor();
        assertEquals(new BigDecimal("10"), def.getBaseBlockReward());
    }
}