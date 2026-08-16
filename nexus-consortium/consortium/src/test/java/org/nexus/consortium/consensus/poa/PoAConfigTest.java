package org.nexus.consortium.consensus.poa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PoAConfig 单元测试。
 * 覆盖构造器、getter/setter、默认值。
 */
public class PoAConfigTest {

    @Test
    public void testDefaultConstructor() {
        PoAConfig config = new PoAConfig();
        assertNotNull(config);
        assertNull(config.getName());
        assertNull(config.getGenesis());
        assertEquals(0, config.getBlockInterval());
        assertFalse(config.isEnableMining());
        assertNull(config.getMinerCoinBase());
    }

    @Test
    public void testAllArgsConstructor() {
        PoAConfig config = new PoAConfig("poa", "genesis", 10, true, "coinbase");
        assertEquals("poa", config.getName());
        assertEquals("genesis", config.getGenesis());
        assertEquals(10, config.getBlockInterval());
        assertTrue(config.isEnableMining());
        assertEquals("coinbase", config.getMinerCoinBase());
    }

    @Test
    public void testSetters() {
        PoAConfig config = new PoAConfig();
        config.setName("poa");
        config.setGenesis("genesis");
        config.setBlockInterval(5);
        config.setEnableMining(true);
        config.setMinerCoinBase("miner");

        assertEquals("poa", config.getName());
        assertEquals("genesis", config.getGenesis());
        assertEquals(5, config.getBlockInterval());
        assertTrue(config.isEnableMining());
        assertEquals("miner", config.getMinerCoinBase());
    }

    @Test
    public void testEnableMiningFalse() {
        PoAConfig config = new PoAConfig();
        config.setEnableMining(false);
        assertFalse(config.isEnableMining());
    }

    @Test
    public void testZeroBlockInterval() {
        PoAConfig config = new PoAConfig();
        config.setBlockInterval(0);
        assertEquals(0, config.getBlockInterval());
    }

    @Test
    public void testNegativeBlockInterval() {
        PoAConfig config = new PoAConfig();
        config.setBlockInterval(-1);
        assertEquals(-1, config.getBlockInterval());
    }
}