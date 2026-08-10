package org.nexus.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Packet} 单元测试。
 */
class PacketTest {

    @Test
    void defaultConstructorCreatesEmpty() {
        Packet p = new Packet();
        assertNull(p.data);
        assertEquals(0, p.ttl);
    }

    @Test
    void fullConstructorSetsFields() {
        byte[] data = new byte[]{1, 2, 3};
        Packet p = new Packet(data, 10);
        assertArrayEquals(data, p.data);
        assertEquals(10, p.ttl);
    }

    @Test
    void decDecrementsTtl() {
        Packet p = new Packet(null, 5);
        p.dec();
        assertEquals(4, p.ttl);
        p.dec();
        assertEquals(3, p.ttl);
    }

    @Test
    void decStopsAtZero() {
        Packet p = new Packet(null, 1);
        p.dec();
        assertEquals(0, p.ttl);
        p.dec();
        assertEquals(0, p.ttl); // 不应为负
    }

    @Test
    void decOnZeroStaysZero() {
        Packet p = new Packet(null, 0);
        p.dec();
        assertEquals(0, p.ttl);
    }
}