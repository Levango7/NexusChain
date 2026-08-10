package org.nexus.l2.zk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ZkProof} 单元测试。
 */
class ZkProofTest {

    @Test
    void nullProofDataBecomesEmptyArray() {
        ZkProof p = new ZkProof(null, "c1", 1, 100L);
        assertEquals(0, p.size());
        assertArrayEquals(new byte[0], p.getProofData());
    }

    @Test
    void gettersReturnValues() {
        byte[] data = {1, 2, 3, 4};
        ZkProof p = new ZkProof(data, "circuit-1", 2, 12345L);
        assertArrayEquals(data, p.getProofData());
        assertEquals("circuit-1", p.getCircuitId());
        assertEquals(2, p.getSetupVersion());
        assertEquals(12345L, p.getCreatedAt());
        assertEquals(4, p.size());
    }

    @Test
    void getProofDataReturnsDefensiveCopy() {
        byte[] data = {1, 2, 3};
        ZkProof p = new ZkProof(data, "c", 1, 0);
        byte[] got = p.getProofData();
        got[0] = 99;
        // 修改返回值不影响内部
        assertArrayEquals(data, p.getProofData());
    }

    @Test
    void constructorClonesInputArray() {
        byte[] data = {1, 2, 3};
        ZkProof p = new ZkProof(data, "c", 1, 0);
        data[0] = 99;
        // 修改原数组不影响证明
        assertEquals(1, p.getProofData()[0]);
    }

    @Test
    void toStringContainsKeyInfo() {
        ZkProof p = new ZkProof(new byte[]{1, 2}, "cid", 3, 100L);
        String s = p.toString();
        assertTrue(s.contains("cid"));
        assertTrue(s.contains("ZkProof"));
        assertTrue(s.contains("size=2"));
    }

    @Test
    void equalsSameDataReturnsTrue() {
        byte[] d1 = {1, 2, 3};
        byte[] d2 = {1, 2, 3};
        ZkProof a = new ZkProof(d1, "c", 1, 100L);
        ZkProof b = new ZkProof(d2, "c", 1, 999L); // createdAt 不参与 equals
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equalsDifferentDataReturnsFalse() {
        ZkProof a = new ZkProof(new byte[]{1}, "c", 1, 0);
        ZkProof b = new ZkProof(new byte[]{2}, "c", 1, 0);
        assertNotEquals(a, b);
    }

    @Test
    void equalsDifferentCircuitReturnsFalse() {
        ZkProof a = new ZkProof(new byte[]{1}, "c1", 1, 0);
        ZkProof b = new ZkProof(new byte[]{1}, "c2", 1, 0);
        assertNotEquals(a, b);
    }

    @Test
    void equalsDifferentSetupVersionReturnsFalse() {
        ZkProof a = new ZkProof(new byte[]{1}, "c", 1, 0);
        ZkProof b = new ZkProof(new byte[]{1}, "c", 2, 0);
        assertNotEquals(a, b);
    }

    @Test
    void equalsNullCircuitIdHandled() {
        ZkProof a = new ZkProof(new byte[]{1}, null, 1, 0);
        ZkProof b = new ZkProof(new byte[]{1}, null, 1, 0);
        assertEquals(a, b);
        ZkProof c = new ZkProof(new byte[]{1}, "c", 1, 0);
        assertNotEquals(a, c);
    }

    @Test
    void equalsReflexiveAndNull() {
        ZkProof a = new ZkProof(new byte[]{1}, "c", 1, 0);
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "not a ZkProof");
    }
}