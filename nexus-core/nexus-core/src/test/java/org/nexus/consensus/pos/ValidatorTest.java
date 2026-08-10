package org.nexus.consensus.pos;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Validator} 实体测试。
 *
 * <p>覆盖默认构造器、全参数构造器、getter/setter 与状态枚举。</p>
 */
public class ValidatorTest {

    private static final String ADDRESS = "0xabc123";
    private static final String PUB_KEY = "0xpubkey456";
    private static final BigDecimal STAKE = new BigDecimal("1500");
    private static final double COMMISSION = 0.10d;

    @Test
    public void testFullConstructor() {
        Validator v = new Validator(ADDRESS, PUB_KEY, STAKE, COMMISSION, ValidatorStatus.ACTIVE);
        assertEquals(ADDRESS, v.getAddress());
        assertEquals(PUB_KEY, v.getPublicKey());
        assertEquals(STAKE, v.getStakeAmount());
        assertEquals(COMMISSION, v.getCommissionRate(), 0.0001);
        assertEquals(ValidatorStatus.ACTIVE, v.getStatus());
    }

    @Test
    public void testDefaultConstructorAndSetters() {
        Validator v = new Validator();
        v.setAddress(ADDRESS);
        v.setPublicKey(PUB_KEY);
        v.setStakeAmount(STAKE);
        v.setCommissionRate(COMMISSION);
        v.setStatus(ValidatorStatus.SLASHED);

        assertEquals(ADDRESS, v.getAddress());
        assertEquals(PUB_KEY, v.getPublicKey());
        assertEquals(STAKE, v.getStakeAmount());
        assertEquals(COMMISSION, v.getCommissionRate(), 0.0001);
        assertEquals(ValidatorStatus.SLASHED, v.getStatus());
    }

    @Test
    public void testValidatorStatusEnum() {
        ValidatorStatus[] statuses = ValidatorStatus.values();
        assertEquals(3, statuses.length);
        assertSame(ValidatorStatus.ACTIVE, ValidatorStatus.valueOf("ACTIVE"));
        assertSame(ValidatorStatus.INACTIVE, ValidatorStatus.valueOf("INACTIVE"));
        assertSame(ValidatorStatus.SLASHED, ValidatorStatus.valueOf("SLASHED"));
    }

    @Test
    public void testNullValues() {
        Validator v = new Validator();
        assertNull(v.getAddress());
        assertNull(v.getPublicKey());
        assertNull(v.getStakeAmount());
        assertNull(v.getStatus());
        assertEquals(0.0d, v.getCommissionRate(), 0.0001);
    }
}