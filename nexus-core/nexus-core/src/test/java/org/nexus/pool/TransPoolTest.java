package org.nexus.pool;

import org.junit.jupiter.api.Test;
import org.nexus.core.account.Transaction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TransPool} 单元测试。
 */
class TransPoolTest {

    @Test
    void defaultConstructorCreatesEmpty() {
        TransPool t = new TransPool();
        assertNull(t.getTransaction());
        assertEquals(0, t.getState());
        assertEquals(0, t.getDatetime());
        assertEquals(0, t.getHeight());
    }

    @Test
    void fullConstructorSetsFields() {
        Transaction tx = new Transaction();
        TransPool t = new TransPool(tx, 1, 12345L);
        assertSame(tx, t.getTransaction());
        assertEquals(1, t.getState());
        assertEquals(12345L, t.getDatetime());
    }

    @Test
    void settersUpdateFields() {
        TransPool t = new TransPool();
        Transaction tx = new Transaction();
        t.setTransaction(tx);
        t.setState(2);
        t.setDatetime(999L);
        t.setHeight(50);
        assertSame(tx, t.getTransaction());
        assertEquals(2, t.getState());
        assertEquals(999L, t.getDatetime());
        assertEquals(50, t.getHeight());
    }
}