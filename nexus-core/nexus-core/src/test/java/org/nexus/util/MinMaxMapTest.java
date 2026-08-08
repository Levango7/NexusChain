package org.nexus.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MinMaxMap} 单元测试。
 */
class MinMaxMapTest {

    @Test
    void emptyMapGetMinGetMaxReturnNull() {
        MinMaxMap<String> map = new MinMaxMap<>();
        assertNull(map.getMin());
        assertNull(map.getMax());
    }

    @Test
    void getMinAndGetMax() {
        MinMaxMap<String> map = new MinMaxMap<>();
        map.put(5L, "a");
        map.put(1L, "b");
        map.put(10L, "c");
        assertEquals(1L, map.getMin());
        assertEquals(10L, map.getMax());
    }

    @Test
    void clearAllAfterRemovesGreaterKeys() {
        MinMaxMap<String> map = new MinMaxMap<>();
        map.put(1L, "a");
        map.put(5L, "b");
        map.put(10L, "c");
        map.clearAllAfter(5L);
        assertEquals(2, map.size());
        assertTrue(map.containsKey(1L));
        assertTrue(map.containsKey(5L));
        assertFalse(map.containsKey(10L));
    }

    @Test
    void clearAllBeforeRemovesLesserKeys() {
        MinMaxMap<String> map = new MinMaxMap<>();
        map.put(1L, "a");
        map.put(5L, "b");
        map.put(10L, "c");
        map.clearAllBefore(5L);
        assertEquals(2, map.size());
        assertFalse(map.containsKey(1L));
        assertTrue(map.containsKey(5L));
        assertTrue(map.containsKey(10L));
    }

    @Test
    void clearAllAfterOnEmptyMapIsNoOp() {
        MinMaxMap<String> map = new MinMaxMap<>();
        map.clearAllAfter(5L); // 不应抛异常
    }

    @Test
    void clearAllBeforeOnEmptyMapIsNoOp() {
        MinMaxMap<String> map = new MinMaxMap<>();
        map.clearAllBefore(5L); // 不应抛异常
    }
}