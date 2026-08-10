package org.nexus.consensus.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link SlashingService} 惩罚服务测试。
 *
 * <p>使用 Mockito mock {@link ValidatorRegistry} 与 {@link StakingService}，
 * 覆盖按违规类型罚没、按金额罚没、参数校验等场景。</p>
 */
public class SlashingServiceTest {

    private SlashingService slashing;
    private ValidatorRegistry registry;
    private StakingService staking;

    @BeforeEach
    public void setUp() {
        slashing = new SlashingService();
        registry = mock(ValidatorRegistry.class);
        staking = mock(StakingService.class);
        injectField(slashing, "validatorRegistry", registry);
        injectField(slashing, "stakingService", staking);
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
    public void testSlashDoubleSign() {
        Validator v = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getValidator("addr1")).thenReturn(v);
        when(staking.getStake("addr1")).thenReturn(new BigDecimal("1000"));

        BigDecimal slashAmount = slashing.slash("addr1", SlashingService.Offense.DOUBLE_SIGN);
        assertEquals(0, new BigDecimal("1000").compareTo(slashAmount));
        assertEquals(ValidatorStatus.SLASHED, v.getStatus());
        verify(staking).unstake("addr1", slashAmount);
    }

    @Test
    public void testSlashOffline() {
        Validator v = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getValidator("addr1")).thenReturn(v);
        when(staking.getStake("addr1")).thenReturn(new BigDecimal("1000"));

        BigDecimal slashAmount = slashing.slash("addr1", SlashingService.Offense.OFFLINE);
        // OFFLINE rate = 0.01
        assertEquals(new BigDecimal("10.00"), slashAmount);
        // OFFLINE 不应置为 SLASHED
        assertEquals(ValidatorStatus.ACTIVE, v.getStatus());
    }

    @Test
    public void testSlashMalicious() {
        Validator v = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getValidator("addr1")).thenReturn(v);
        when(staking.getStake("addr1")).thenReturn(new BigDecimal("1000"));

        BigDecimal slashAmount = slashing.slash("addr1", SlashingService.Offense.MALICIOUS);
        // MALICIOUS rate = 0.5
        assertEquals(0, new BigDecimal("500").compareTo(slashAmount));
        assertEquals(ValidatorStatus.SLASHED, v.getStatus());
    }

    @Test
    public void testSlashNullArguments() {
        assertEquals(BigDecimal.ZERO, slashing.slash(null, SlashingService.Offense.DOUBLE_SIGN));
        assertEquals(BigDecimal.ZERO, slashing.slash("addr1", null));
    }

    @Test
    public void testSlashUnknownValidator() {
        when(registry.getValidator("unknown")).thenReturn(null);
        assertEquals(BigDecimal.ZERO, slashing.slash("unknown", SlashingService.Offense.DOUBLE_SIGN));
    }

    @Test
    public void testSlashUnstakeThrowsException() {
        Validator v = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getValidator("addr1")).thenReturn(v);
        when(staking.getStake("addr1")).thenReturn(new BigDecimal("1000"));
        doThrow(new IllegalArgumentException("insufficient"))
                .when(staking).unstake(eq("addr1"), any(BigDecimal.class));

        BigDecimal result = slashing.slash("addr1", SlashingService.Offense.DOUBLE_SIGN);
        assertEquals(BigDecimal.ZERO, result);
    }

    @Test
    public void testSlashByAmountSuccess() {
        Validator v = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getValidator("addr1")).thenReturn(v);
        when(staking.getStake("addr1")).thenReturn(new BigDecimal("1000"));

        BigDecimal result = slashing.slash("addr1", new BigDecimal("300"), "FRAUD_PROVEN");
        assertEquals(new BigDecimal("300"), result);
        assertEquals(ValidatorStatus.SLASHED, v.getStatus());
    }

    @Test
    public void testSlashByAmountExceedingStake() {
        Validator v = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getValidator("addr1")).thenReturn(v);
        when(staking.getStake("addr1")).thenReturn(new BigDecimal("500"));

        BigDecimal result = slashing.slash("addr1", new BigDecimal("1000"), "FRAUD_PROVEN");
        // 实际罚没 = min(slashAmount, stake) = 500
        assertEquals(new BigDecimal("500"), result);
    }

    @Test
    public void testSlashByAmountInvalidArguments() {
        assertEquals(BigDecimal.ZERO, slashing.slash(null, BigDecimal.ONE, "reason"));
        assertEquals(BigDecimal.ZERO, slashing.slash("addr1", null, "reason"));
        assertEquals(BigDecimal.ZERO, slashing.slash("addr1", BigDecimal.ZERO, "reason"));
        assertEquals(BigDecimal.ZERO, slashing.slash("addr1", new BigDecimal("-1"), "reason"));
    }

    @Test
    public void testSlashByAmountUnknownValidator() {
        when(registry.getValidator("unknown")).thenReturn(null);
        assertEquals(BigDecimal.ZERO, slashing.slash("unknown", BigDecimal.ONE, "reason"));
    }

    @Test
    public void testSlashByAmountZeroStake() {
        Validator v = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getValidator("addr1")).thenReturn(v);
        when(staking.getStake("addr1")).thenReturn(BigDecimal.ZERO);

        assertEquals(BigDecimal.ZERO, slashing.slash("addr1", BigDecimal.ONE, "reason"));
    }

    @Test
    public void testSlashByAmountUnstakeThrows() {
        Validator v = new Validator("addr1", "pub1", new BigDecimal("1000"), 0.05, ValidatorStatus.ACTIVE);
        when(registry.getValidator("addr1")).thenReturn(v);
        when(staking.getStake("addr1")).thenReturn(new BigDecimal("1000"));
        doThrow(new IllegalArgumentException("error"))
                .when(staking).unstake(eq("addr1"), any(BigDecimal.class));

        assertEquals(BigDecimal.ZERO, slashing.slash("addr1", new BigDecimal("500"), "reason"));
    }

    @Test
    public void testSetAndGetSlashRate() {
        BigDecimal newRate = new BigDecimal("0.25");
        slashing.setSlashRate(SlashingService.Offense.OFFLINE, newRate);
        assertEquals(newRate, slashing.getSlashRate(SlashingService.Offense.OFFLINE));
    }

    @Test
    public void testGetSlashRateDefault() {
        assertEquals(new BigDecimal("1.0"), slashing.getSlashRate(SlashingService.Offense.DOUBLE_SIGN));
        assertEquals(new BigDecimal("0.5"), slashing.getSlashRate(SlashingService.Offense.MALICIOUS));
        assertEquals(new BigDecimal("0.01"), slashing.getSlashRate(SlashingService.Offense.OFFLINE));
        assertEquals(BigDecimal.ZERO, slashing.getSlashRate(null));
    }

    @Test
    public void testOffenseEnum() {
        SlashingService.Offense[] offenses = SlashingService.Offense.values();
        assertEquals(3, offenses.length);
        assertSame(SlashingService.Offense.DOUBLE_SIGN, SlashingService.Offense.valueOf("DOUBLE_SIGN"));
        assertSame(SlashingService.Offense.OFFLINE, SlashingService.Offense.valueOf("OFFLINE"));
        assertSame(SlashingService.Offense.MALICIOUS, SlashingService.Offense.valueOf("MALICIOUS"));
    }
}