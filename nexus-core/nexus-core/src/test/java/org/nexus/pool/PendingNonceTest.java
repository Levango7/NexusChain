package org.nexus.pool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PendingNonce} 单元测试。
 */
class PendingNonceTest {

    @Test
    void defaultConstructorCreatesEmpty() {
        PendingNonce p = new PendingNonce();
        assertEquals(0, p.getNonce());
        assertEquals(0, p.getState());
    }

    @Test
    void fullConstructorSetsFields() {
        PendingNonce p = new PendingNonce(42, 2);
        assertEquals(42, p.getNonce());
        assertEquals(2, p.getState());
    }

    @Test
    void settersUpdateFields() {
        PendingNonce p = new PendingNonce();
        p.setNonce(99);
        p.setState(1);
        assertEquals(99, p.getNonce());
        assertEquals(1, p.getState());
    }
}