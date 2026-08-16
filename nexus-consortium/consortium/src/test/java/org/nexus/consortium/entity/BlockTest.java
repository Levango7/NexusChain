package org.nexus.consortium.entity;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Block 实体类单元测试。
 * 覆盖构造器、encode/decode 往返、body 操作。
 */
public class BlockTest {

    private static final byte[] HASH = new byte[]{1, 2, 3, 4};
    private static final byte[] HASH_PREV = new byte[]{5, 6, 7, 8};
    private static final byte[] MERKLE_ROOT = new byte[]{9, 10, 11, 12};
    private static final byte[] PAYLOAD = new byte[]{13, 14, 15, 16};

    @Test
    public void testDefaultConstructor() {
        Block block = new Block();
        assertNotNull(block);
        assertNull(block.getHash());
        assertNull(block.getBody());
    }

    @Test
    public void testFullConstructor() {
        Block block = new Block(HASH, 1, HASH_PREV, MERKLE_ROOT, 100L, 200L, PAYLOAD);
        assertArrayEquals(HASH, block.getHash());
        assertEquals(1, block.getVersion());
        assertArrayEquals(HASH_PREV, block.getHashPrev());
        assertArrayEquals(MERKLE_ROOT, block.getMerkleRoot());
        assertEquals(100L, block.getHeight());
        assertEquals(200L, block.getCreatedAt());
        assertArrayEquals(PAYLOAD, block.getPayload());
    }

    @Test
    public void testHeaderAdapterConstructor() {
        HeaderAdapter adapter = new HeaderAdapter(
                HASH, 1, HASH_PREV, MERKLE_ROOT, 100L, 200L, PAYLOAD);
        Block block = new Block(adapter);
        assertArrayEquals(HASH, block.getHash());
        assertEquals(1, block.getVersion());
        assertArrayEquals(HASH_PREV, block.getHashPrev());
        assertArrayEquals(MERKLE_ROOT, block.getMerkleRoot());
        assertEquals(100L, block.getHeight());
        assertEquals(200L, block.getCreatedAt());
        assertArrayEquals(PAYLOAD, block.getPayload());
    }

    @Test
    public void testBodySetterAndGetter() {
        Block block = new Block(HASH, 1, HASH_PREV, MERKLE_ROOT, 100L, 200L, PAYLOAD);
        List<Transaction> body = new ArrayList<>();
        block.setBody(body);
        assertNotNull(block.getBody());
        assertEquals(0, block.getBody().size());

        Transaction tx = Transaction.builder()
                .blockHash(HASH)
                .height(100L)
                .hash(new byte[]{1})
                .version(1)
                .type(0)
                .createdAt(200L)
                .nonce(1L)
                .from(HASH)
                .gasPrice(0L)
                .amount(100L)
                .payload(PAYLOAD)
                .to(HASH)
                .signature(PAYLOAD)
                .position(0)
                .build();
        body.add(tx);
        block.setBody(body);
        assertEquals(1, block.getBody().size());
    }

    @Test
    public void testEncodeDecodeRoundTrip() {
        Block block = new Block(HASH, 1, HASH_PREV, MERKLE_ROOT, 100L, 200L, PAYLOAD);
        List<Transaction> body = new ArrayList<>();
        Transaction tx = Transaction.builder()
                .blockHash(HASH)
                .height(100L)
                .hash(new byte[]{1})
                .version(1)
                .type(0)
                .createdAt(200L)
                .nonce(1L)
                .from(HASH)
                .gasPrice(0L)
                .amount(100L)
                .payload(PAYLOAD)
                .to(HASH)
                .signature(PAYLOAD)
                .position(0)
                .build();
        body.add(tx);
        block.setBody(body);

        byte[] encoded = block.encode();
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        Block decoded = Block.decode(encoded);
        assertNotNull(decoded);
        assertEquals(1, decoded.getVersion());
        assertEquals(100L, decoded.getHeight());
        assertEquals(200L, decoded.getCreatedAt());
    }

    @Test
    public void testDecodeInvalidData() {
        Block decoded = Block.decode(new byte[]{0, 1, 2, 3});
        assertNull(decoded);
    }

    @Test
    public void testDecodeEmptyData() {
        Block decoded = Block.decode(new byte[0]);
        assertNull(decoded);
    }
}