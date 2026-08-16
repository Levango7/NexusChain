package org.nexus.consortium.consensus.poa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PoA 单元测试。
 * 覆盖基本构造与接口实现。
 */
public class PoATest {

    @Test
    public void testDefaultConstructor() {
        PoA poa = new PoA();
        assertNotNull(poa);
    }

    @Test
    public void testImplementsConsensusEngine() {
        PoA poa = new PoA();
        assertTrue(poa instanceof org.nexus.common.ConsensusEngine);
    }

    @Test
    public void testImplementsPeerServerListener() {
        PoA poa = new PoA();
        assertTrue(poa instanceof org.nexus.common.PeerServerListener);
    }

    @Test
    public void testPolicy() {
        PoA poa = new PoA();
        org.nexus.common.HashPolicy policy = poa.policy();
        assertNotNull(policy);
    }

    @Test
    public void testMiner() {
        PoA poa = new PoA();
        org.nexus.common.Miner miner = poa.miner();
        assertNull(miner);
    }
}