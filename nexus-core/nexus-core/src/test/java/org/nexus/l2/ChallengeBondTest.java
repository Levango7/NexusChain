package org.nexus.l2;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ChallengeBond} 实体测试。
 */
public class ChallengeBondTest {

    @Test
    public void testDefaultConstructor() {
        ChallengeBond bond = new ChallengeBond();
        assertNull(bond.getChallengerId());
        assertNull(bond.getAmount());
        assertNull(bond.getStatus());
        assertEquals(0L, bond.getStakeTime());
    }

    @Test
    public void testFullConstructor() {
        long before = System.currentTimeMillis();
        ChallengeBond bond = new ChallengeBond("challenger1", new BigDecimal("1000"));
        long after = System.currentTimeMillis();

        assertEquals("challenger1", bond.getChallengerId());
        assertEquals(new BigDecimal("1000"), bond.getAmount());
        assertEquals(ChallengeBond.Status.STAKED, bond.getStatus());
        assertTrue(bond.getStakeTime() >= before && bond.getStakeTime() <= after);
    }

    @Test
    public void testSetters() {
        ChallengeBond bond = new ChallengeBond();
        bond.setChallengerId("challenger2");
        bond.setAmount(new BigDecimal("500"));
        bond.setStatus(ChallengeBond.Status.RELEASED);
        bond.setStakeTime(12345L);

        assertEquals("challenger2", bond.getChallengerId());
        assertEquals(new BigDecimal("500"), bond.getAmount());
        assertEquals(ChallengeBond.Status.RELEASED, bond.getStatus());
        assertEquals(12345L, bond.getStakeTime());
    }

    @Test
    public void testStatusEnum() {
        ChallengeBond.Status[] statuses = ChallengeBond.Status.values();
        assertEquals(3, statuses.length);
        assertSame(ChallengeBond.Status.STAKED, ChallengeBond.Status.valueOf("STAKED"));
        assertSame(ChallengeBond.Status.RELEASED, ChallengeBond.Status.valueOf("RELEASED"));
        assertSame(ChallengeBond.Status.SLASHED, ChallengeBond.Status.valueOf("SLASHED"));
    }
}