package org.nexus.signing.pool;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link NonceState} 单元测试。
 *
 * <p>覆盖构造函数、getter/setter、状态判断方法。</p>
 */
public class NonceStateTest {

    @Test
    public void testDefaultConstructor_setsDefaultStatus() {
        NonceState state = new NonceState();
        assertEquals(NonceState.STATUS_AVAILABLE, state.getStatus());
        assertFalse(state.isLocked());
        assertFalse(state.isUsed());
    }

    @Test
    public void testThreeArgConstructor_setsDefaultStatus() {
        NonceState state = new NonceState("0xabc", 5L, 1234567890L);
        assertEquals("0xabc", state.getTranHash());
        assertEquals(5L, state.getNonce());
        assertEquals(1234567890L, state.getDatetime());
        assertEquals(NonceState.STATUS_AVAILABLE, state.getStatus());
        assertFalse(state.isLocked());
        assertFalse(state.isUsed());
    }

    @Test
    public void testFourArgConstructor_withLockedStatus() {
        NonceState state = new NonceState("0xdef", 10L, 9876543210L, NonceState.STATUS_LOCKED);
        assertEquals("0xdef", state.getTranHash());
        assertEquals(10L, state.getNonce());
        assertEquals(9876543210L, state.getDatetime());
        assertEquals(NonceState.STATUS_LOCKED, state.getStatus());
        assertTrue(state.isLocked());
        assertFalse(state.isUsed());
    }

    @Test
    public void testFourArgConstructor_withUsedStatus() {
        NonceState state = new NonceState("0xghi", 15L, 5555555555L, NonceState.STATUS_USED);
        assertEquals(NonceState.STATUS_USED, state.getStatus());
        assertFalse(state.isLocked());
        assertTrue(state.isUsed());
    }

    @Test
    public void testFourArgConstructor_withNullStatus_defaultsToAvailable() {
        NonceState state = new NonceState("0x", 0L, 0L, null);
        assertEquals(NonceState.STATUS_AVAILABLE, state.getStatus());
        assertFalse(state.isLocked());
        assertFalse(state.isUsed());
    }

    @Test
    public void testSetters() {
        NonceState state = new NonceState();
        state.setTranHash("0xset");
        state.setNonce(42L);
        state.setDatetime(999L);
        state.setStatus(NonceState.STATUS_LOCKED);
        assertEquals("0xset", state.getTranHash());
        assertEquals(42L, state.getNonce());
        assertEquals(999L, state.getDatetime());
        assertEquals(NonceState.STATUS_LOCKED, state.getStatus());
        assertTrue(state.isLocked());
    }

    @Test
    public void testStatusConstants() {
        assertEquals("AVAILABLE", NonceState.STATUS_AVAILABLE);
        assertEquals("LOCKED", NonceState.STATUS_LOCKED);
        assertEquals("USED", NonceState.STATUS_USED);
    }

    @Test
    public void testIsLocked_falseForNonLockedStatus() {
        NonceState state = new NonceState();
        state.setStatus("CUSTOM");
        assertFalse(state.isLocked());
        assertFalse(state.isUsed());
    }
}