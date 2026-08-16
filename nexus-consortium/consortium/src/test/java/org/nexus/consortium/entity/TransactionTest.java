package org.nexus.consortium.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transaction 实体类单元测试。
 * 覆盖构造器、builder、encode/decode 往返、字段访问。
 */
public class TransactionTest {

    private static final byte[] HASH = new byte[]{1, 2, 3, 4};
    private static final byte[] FROM = new byte[]{5, 6, 7, 8};
    private static final byte[] TO = new byte[]{9, 10, 11, 12};
    private static final byte[] PAYLOAD = new byte[]{13, 14, 15, 16};
    private static final byte[] SIGNATURE = new byte[]{17, 18, 19, 20};

    @Test
    public void testDefaultConstructor() {
        Transaction tx = new Transaction();
        assertNotNull(tx);
        assertNull(tx.getHash());
        assertNull(tx.getFrom());
    }

    @Test
    public void testBuilder() {
        Transaction tx = Transaction.builder()
                .blockHash(HASH)
                .height(100L)
                .hash(new byte[]{1})
                .version(1)
                .type(0)
                .createdAt(200L)
                .nonce(1L)
                .from(FROM)
                .gasPrice(0L)
                .amount(500L)
                .payload(PAYLOAD)
                .to(TO)
                .signature(SIGNATURE)
                .position(0)
                .build();

        assertArrayEquals(HASH, tx.getBlockHash());
        assertEquals(100L, tx.getHeight());
        assertArrayEquals(new byte[]{1}, tx.getHash());
        assertEquals(1, tx.getVersion());
        assertEquals(0, tx.getType());
        assertEquals(200L, tx.getCreatedAt());
        assertEquals(1L, tx.getNonce());
        assertArrayEquals(FROM, tx.getFrom());
        assertEquals(0L, tx.getGasPrice());
        assertEquals(500L, tx.getAmount());
        assertArrayEquals(PAYLOAD, tx.getPayload());
        assertArrayEquals(TO, tx.getTo());
        assertArrayEquals(SIGNATURE, tx.getSignature());
        assertEquals(0, tx.getPosition());
    }

    @Test
    public void testSetters() {
        Transaction tx = new Transaction();
        tx.setBlockHash(HASH);
        tx.setHeight(100L);
        tx.setHash(new byte[]{1});
        tx.setVersion(1);
        tx.setType(0);
        tx.setCreatedAt(200L);
        tx.setNonce(1L);
        tx.setFrom(FROM);
        tx.setGasPrice(0L);
        tx.setAmount(500L);
        tx.setPayload(PAYLOAD);
        tx.setTo(TO);
        tx.setSignature(SIGNATURE);
        tx.setPosition(0);

        assertArrayEquals(HASH, tx.getBlockHash());
        assertEquals(100L, tx.getHeight());
        assertEquals(500L, tx.getAmount());
    }

    @Test
    public void testEncodeDecodeRoundTrip() {
        Transaction tx = Transaction.builder()
                .blockHash(HASH)
                .height(100L)
                .hash(new byte[]{1})
                .version(1)
                .type(1)
                .createdAt(200L)
                .nonce(1L)
                .from(FROM)
                .gasPrice(1L)
                .amount(500L)
                .payload(PAYLOAD)
                .to(TO)
                .signature(SIGNATURE)
                .position(1)
                .build();

        byte[] encoded = tx.encode();
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        Transaction decoded = Transaction.decode(encoded);
        assertNotNull(decoded);
        assertEquals(100L, decoded.getHeight());
        assertEquals(1, decoded.getVersion());
        assertEquals(1, decoded.getType());
        assertEquals(200L, decoded.getCreatedAt());
        assertEquals(1L, decoded.getNonce());
        assertEquals(1L, decoded.getGasPrice());
        assertEquals(500L, decoded.getAmount());
        assertEquals(1, decoded.getPosition());
    }

    @Test
    public void testDecodeInvalidData() {
        Transaction decoded = Transaction.decode(new byte[]{0, 1, 2, 3});
        assertNull(decoded);
    }

    @Test
    public void testDecodeEmptyData() {
        Transaction decoded = Transaction.decode(new byte[0]);
        assertNull(decoded);
    }

    @Test
    public void testZeroAmount() {
        Transaction tx = Transaction.builder()
                .blockHash(HASH)
                .height(0L)
                .hash(new byte[]{1})
                .version(1)
                .type(0)
                .createdAt(0L)
                .nonce(0L)
                .from(FROM)
                .gasPrice(0L)
                .amount(0L)
                .payload(PAYLOAD)
                .to(TO)
                .signature(SIGNATURE)
                .position(0)
                .build();
        assertEquals(0L, tx.getAmount());
    }
}