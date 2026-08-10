package org.nexus.l2;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link L2Transaction} 实体测试。
 */
public class L2TransactionTest {

    @Test
    public void testDefaultConstructor() {
        L2Transaction tx = new L2Transaction();
        assertNull(tx.getTxHash());
        assertNull(tx.getBatchId());
        assertNull(tx.getRawTx());
        assertNull(tx.getAmount());
        assertNull(tx.getStatus());
        assertNull(tx.getSender());
        assertEquals(0L, tx.getNonce());
        assertEquals(BigInteger.ZERO, tx.getPriorityFee());
        assertEquals(0L, tx.getGasLimit());
        assertNull(tx.getRecipient());
    }

    @Test
    public void testSettersAndGetters() {
        L2Transaction tx = new L2Transaction();
        tx.setTxHash("0xabc");
        tx.setBatchId(42L);
        tx.setRawTx(new byte[]{1, 2, 3});
        tx.setAmount(BigInteger.valueOf(1000));
        tx.setStatus(L2TransactionStatus.PENDING);
        tx.setSender("senderAddr");
        tx.setNonce(5L);
        tx.setPriorityFee(BigInteger.valueOf(100));
        tx.setGasLimit(21000L);
        tx.setRecipient("recipientAddr");

        assertEquals("0xabc", tx.getTxHash());
        assertEquals(42L, tx.getBatchId());
        assertArrayEquals(new byte[]{1, 2, 3}, tx.getRawTx());
        assertEquals(BigInteger.valueOf(1000), tx.getAmount());
        assertEquals(L2TransactionStatus.PENDING, tx.getStatus());
        assertEquals("senderAddr", tx.getSender());
        assertEquals(5L, tx.getNonce());
        assertEquals(BigInteger.valueOf(100), tx.getPriorityFee());
        assertEquals(21000L, tx.getGasLimit());
        assertEquals("recipientAddr", tx.getRecipient());
    }

    @Test
    public void testSetNullPriorityFee() {
        L2Transaction tx = new L2Transaction();
        tx.setPriorityFee(null);
        assertEquals(BigInteger.ZERO, tx.getPriorityFee());
    }

    @Test
    public void testL2TransactionStatusEnum() {
        L2TransactionStatus[] statuses = L2TransactionStatus.values();
        assertEquals(4, statuses.length);
        assertSame(L2TransactionStatus.PENDING, L2TransactionStatus.valueOf("PENDING"));
        assertSame(L2TransactionStatus.INCLUDED, L2TransactionStatus.valueOf("INCLUDED"));
        assertSame(L2TransactionStatus.CONFIRMED, L2TransactionStatus.valueOf("CONFIRMED"));
        assertSame(L2TransactionStatus.REVERTED, L2TransactionStatus.valueOf("REVERTED"));
    }
}