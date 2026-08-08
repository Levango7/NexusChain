package org.nexus.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConsensuEntity} 及其内部 {@link ConsensuEntity.Status} 单元测试。
 */
class ConsensuEntityTest {

    @Test
    void statusFieldsAreMutable() {
        ConsensuEntity.Status s = new ConsensuEntity.Status();
        s.version = 1;
        s.currentHeight = 100;
        s.bestBlockHash = new byte[]{1, 2};
        s.genesisHash = new byte[]{3, 4};

        assertEquals(1, s.version);
        assertEquals(100, s.currentHeight);
        assertArrayEquals(new byte[]{1, 2}, s.bestBlockHash);
        assertArrayEquals(new byte[]{3, 4}, s.genesisHash);
    }

    @Test
    void statusDefaultsZero() {
        ConsensuEntity.Status s = new ConsensuEntity.Status();
        assertEquals(0, s.version);
        assertEquals(0, s.currentHeight);
        assertNull(s.bestBlockHash);
        assertNull(s.genesisHash);
    }
}