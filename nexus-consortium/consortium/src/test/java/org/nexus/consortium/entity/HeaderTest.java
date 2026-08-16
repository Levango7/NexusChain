package org.nexus.consortium.entity;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Header 实体类单元测试。
 * Header 继承 HeaderAdapter，主要测试继承行为与默认构造。
 */
public class HeaderTest {

    private static final byte[] HASH = new byte[]{1, 2, 3, 4};
    private static final byte[] HASH_PREV = new byte[]{5, 6, 7, 8};
    private static final byte[] MERKLE_ROOT = new byte[]{9, 10, 11, 12};
    private static final byte[] PAYLOAD = new byte[]{13, 14, 15, 16};

    @Test
    public void testDefaultConstructor() {
        Header header = new Header();
        assertNotNull(header);
        assertNull(header.getHash());
        assertEquals(0, header.getVersion());
        assertEquals(0L, header.getHeight());
    }

    @Test
    public void testInheritedSetters() {
        Header header = new Header();
        header.setHash(HASH);
        header.setVersion(1);
        header.setHashPrev(HASH_PREV);
        header.setMerkleRoot(MERKLE_ROOT);
        header.setHeight(100L);
        header.setCreatedAt(200L);
        header.setPayload(PAYLOAD);

        assertArrayEquals(HASH, header.getHash());
        assertEquals(1, header.getVersion());
        assertArrayEquals(HASH_PREV, header.getHashPrev());
        assertArrayEquals(MERKLE_ROOT, header.getMerkleRoot());
        assertEquals(100L, header.getHeight());
        assertEquals(200L, header.getCreatedAt());
        assertArrayEquals(PAYLOAD, header.getPayload());
    }

    @Test
    public void testBuilderInheritance() {
        // Header 继承 HeaderAdapter，builder 返回 HeaderAdapter
        HeaderAdapter adapter = Header.builder()
                .hash(HASH)
                .version(2)
                .hashPrev(HASH_PREV)
                .merkleRoot(MERKLE_ROOT)
                .height(300L)
                .createdAt(400L)
                .payload(PAYLOAD)
                .build();

        assertArrayEquals(HASH, adapter.getHash());
        assertEquals(2, adapter.getVersion());
        assertEquals(300L, adapter.getHeight());
    }

    @Test
    public void testNullFields() {
        Header header = new Header();
        assertNull(header.getHash());
        assertNull(header.getHashPrev());
        assertNull(header.getMerkleRoot());
        assertNull(header.getPayload());
    }
}