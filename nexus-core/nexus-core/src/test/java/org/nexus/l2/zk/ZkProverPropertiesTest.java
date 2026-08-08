package org.nexus.l2.zk;

import org.junit.jupiter.api.Test;
import org.nexus.l2.zk.ZkProverProperties.BackendType;
import org.nexus.l2.zk.ZkProverProperties.Circuit;
import org.nexus.l2.zk.ZkProverProperties.Groth16;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ZkProverProperties} 单元测试。
 */
class ZkProverPropertiesTest {

    @Test
    void defaultsAreSet() {
        ZkProverProperties p = new ZkProverProperties();
        assertTrue(p.isEnabled());
        assertEquals("groth16", p.getBackend());
        assertNotNull(p.getCircuit());
        assertNotNull(p.getGroth16());
        assertEquals(1000, p.getCircuit().getMaxBatchSize());
        assertEquals("secp256k1", p.getGroth16().getCurve());
    }

    @Test
    void settersUpdateValues() {
        ZkProverProperties p = new ZkProverProperties();
        p.setEnabled(false);
        p.setBackend("mock");
        Circuit c = new Circuit();
        c.setMaxBatchSize(500);
        p.setCircuit(c);
        Groth16 g = new Groth16();
        g.setCurve("bn254");
        p.setGroth16(g);

        assertFalse(p.isEnabled());
        assertEquals("mock", p.getBackend());
        assertEquals(500, p.getCircuit().getMaxBatchSize());
        assertEquals("bn254", p.getGroth16().getCurve());
    }

    @Test
    void resolveBackendGroth16() {
        ZkProverProperties p = new ZkProverProperties();
        p.setBackend("groth16");
        assertEquals(BackendType.GROTH16, p.resolveBackend());
    }

    @Test
    void resolveBackendPlonk() {
        ZkProverProperties p = new ZkProverProperties();
        p.setBackend("plonk");
        assertEquals(BackendType.PLONK, p.resolveBackend());
    }

    @Test
    void resolveBackendHalo2() {
        ZkProverProperties p = new ZkProverProperties();
        p.setBackend("halo2");
        assertEquals(BackendType.HALO2, p.resolveBackend());
    }

    @Test
    void resolveBackendMock() {
        ZkProverProperties p = new ZkProverProperties();
        p.setBackend("mock");
        assertEquals(BackendType.MOCK, p.resolveBackend());
    }

    @Test
    void resolveBackendUnknownFallsBackToGroth16() {
        ZkProverProperties p = new ZkProverProperties();
        p.setBackend("unknown");
        assertEquals(BackendType.GROTH16, p.resolveBackend());
    }

    @Test
    void resolveBackendNullFallsBackToGroth16() {
        ZkProverProperties p = new ZkProverProperties();
        p.setBackend(null);
        assertEquals(BackendType.GROTH16, p.resolveBackend());
    }

    @Test
    void resolveBackendCaseInsensitiveAndTrimmed() {
        ZkProverProperties p = new ZkProverProperties();
        p.setBackend("  PLONK  ");
        assertEquals(BackendType.PLONK, p.resolveBackend());
    }

    @Test
    void circuitDefaultsAndSetter() {
        Circuit c = new Circuit();
        assertEquals(1000, c.getMaxBatchSize());
        c.setMaxBatchSize(-1);
        assertEquals(-1, c.getMaxBatchSize());
    }

    @Test
    void groth16DefaultsAndSetter() {
        Groth16 g = new Groth16();
        assertEquals("secp256k1", g.getCurve());
        g.setCurve("alt");
        assertEquals("alt", g.getCurve());
    }
}