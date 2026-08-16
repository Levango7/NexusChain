package org.nexus.consortium.entity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HeaderAdapter 实体类单元测试。
 * 覆盖构造器、getter/setter、equals 基础语义。
 */
public class HeaderAdapterTest {

    private static final byte[] HASH = new byte[]{1, 2, 3, 4};
    private static final byte[] HASH_PREV = new byte[]{5, 6, 7, 8};
    private static final byte[] MERKLE_ROOT = new byte[]{9, 10, 11, 12};
    private static final byte[] PAYLOAD = new byte[]{13, 14, 15, 16};

    @Test
    public void testAllArgsConstructor() {
        HeaderAdapter adapter = new HeaderAdapter(
                HASH, 1, HASH_PREV, MERKLE_ROOT, 100L, 200L, PAYLOAD);
        assertArrayEquals(HASH, adapter.getHash());
        assertEquals(1, adapter.getVersion());
        assertArrayEquals(HASH_PREV, adapter.getHashPrev());
        assertArrayEquals(MERKLE_ROOT, adapter.getMerkleRoot());
        assertEquals(100L, adapter.getHeight());
        assertEquals(200L, adapter.getCreatedAt());
        assertArrayEquals(PAYLOAD, adapter.getPayload());
    }

    @Test
    public void testNoArgsConstructorAndSetters() {
        HeaderAdapter adapter = new HeaderAdapter();
        adapter.setHash(HASH);
        adapter.setVersion(2);
        adapter.setHashPrev(HASH_PREV);
        adapter.setMerkleRoot(MERKLE_ROOT);
        adapter.setHeight(300L);
        adapter.setCreatedAt(400L);
        adapter.setPayload(PAYLOAD);

        assertArrayEquals(HASH, adapter.getHash());
        assertEquals(2, adapter.getVersion());
        assertArrayEquals(HASH_PREV, adapter.getHashPrev());
        assertArrayEquals(MERKLE_ROOT, adapter.getMerkleRoot());
        assertEquals(300L, adapter.getHeight());
        assertEquals(400L, adapter.getCreatedAt());
        assertArrayEquals(PAYLOAD, adapter.getPayload());
    }

    @Test
    public void testBuilder() {
        HeaderAdapter adapter = HeaderAdapter.builder()
                .hash(HASH)
                .version(3)
                .hashPrev(HASH_PREV)
                .merkleRoot(MERKLE_ROOT)
                .height(500L)
                .createdAt(600L)
                .payload(PAYLOAD)
                .build();

        assertArrayEquals(HASH, adapter.getHash());
        assertEquals(3, adapter.getVersion());
        assertEquals(500L, adapter.getHeight());
        assertEquals(600L, adapter.getCreatedAt());
    }

    @Test
    public void testZeroValues() {
        HeaderAdapter adapter = new HeaderAdapter(
                new byte[0], 0, new byte[0], new byte[0], 0L, 0L, new byte[0]);
        assertEquals(0, adapter.getVersion());
        assertEquals(0L, adapter.getHeight());
        assertEquals(0L, adapter.getCreatedAt());
        assertEquals(0, adapter.getHash().length);
    }

    @Test
    public void testNegativeHeight() {
        HeaderAdapter adapter = new HeaderAdapter(
                HASH, 1, HASH_PREV, MERKLE_ROOT, -1L, 200L, PAYLOAD);
        assertEquals(-1L, adapter.getHeight());
    }

    @Test
    public void testMaxValues() {
        HeaderAdapter adapter = new HeaderAdapter(
                HASH, Integer.MAX_VALUE, HASH_PREV, MERKLE_ROOT,
                Long.MAX_VALUE, Long.MAX_VALUE, PAYLOAD);
        assertEquals(Integer.MAX_VALUE, adapter.getVersion());
        assertEquals(Long.MAX_VALUE, adapter.getHeight());
        assertEquals(Long.MAX_VALUE, adapter.getCreatedAt());
    }
}