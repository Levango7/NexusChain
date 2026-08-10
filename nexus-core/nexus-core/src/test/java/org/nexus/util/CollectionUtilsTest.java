package org.nexus.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CollectionUtils} 单元测试。
 */
class CollectionUtilsTest {

    @Test
    void truncateLimitLessThanSize() {
        List<Integer> items = Arrays.asList(1, 2, 3, 4, 5);
        List<Integer> result = CollectionUtils.truncate(items, 3);
        assertEquals(3, result.size());
        assertEquals(1, result.get(0));
        assertEquals(2, result.get(1));
        assertEquals(3, result.get(2));
    }

    @Test
    void truncateLimitGreaterThanSize() {
        List<Integer> items = Arrays.asList(1, 2, 3);
        List<Integer> result = CollectionUtils.truncate(items, 10);
        assertEquals(3, result.size());
    }

    @Test
    void truncateLimitEqualsSize() {
        List<Integer> items = Arrays.asList(1, 2, 3);
        List<Integer> result = CollectionUtils.truncate(items, 3);
        assertEquals(3, result.size());
    }

    @Test
    void truncateReturnsNewList() {
        List<Integer> items = Arrays.asList(1, 2, 3);
        List<Integer> result = CollectionUtils.truncate(items, 3);
        assertNotSame(items, result);
    }

    @Test
    void truncateRandReturnsCorrectSize() {
        List<Integer> items = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        List<Integer> result = CollectionUtils.truncateRand(items, 5);
        assertEquals(5, result.size());
    }

    @Test
    void truncateRandLimitGreaterThanSize() {
        List<Integer> items = Arrays.asList(1, 2, 3);
        List<Integer> result = CollectionUtils.truncateRand(items, 10);
        assertEquals(3, result.size());
    }

    @Test
    void truncateRandSmallLimit() {
        List<Integer> items = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        List<Integer> result = CollectionUtils.truncateRand(items, 2);
        assertEquals(2, result.size());
        // 所有结果应来自原列表
        for (Integer i : result) {
            assertTrue(items.contains(i));
        }
    }

    @Test
    void truncateRandAllElementsFromOriginal() {
        List<String> items = Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h");
        List<String> result = CollectionUtils.truncateRand(items, 4);
        assertEquals(4, result.size());
        for (String s : result) {
            assertTrue(items.contains(s));
        }
    }
}