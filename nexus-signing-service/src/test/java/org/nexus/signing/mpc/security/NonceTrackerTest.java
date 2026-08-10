package org.nexus.signing.mpc.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NonceTracker} 单元测试。
 */
public class NonceTrackerTest {

    @Test
    public void testCheckAndRecordNewNoncePasses() {
        NonceTracker tracker = new NonceTracker();
        long now = System.currentTimeMillis();
        assertTrue(tracker.checkAndRecord("p1", "nonce-1", now));
    }

    @Test
    public void testCheckAndRecordDuplicateNonceFails() {
        NonceTracker tracker = new NonceTracker();
        long now = System.currentTimeMillis();
        assertTrue(tracker.checkAndRecord("p1", "nonce-1", now));
        assertFalse(tracker.checkAndRecord("p1", "nonce-1", now));
    }

    @Test
    public void testDifferentSendersIndependent() {
        NonceTracker tracker = new NonceTracker();
        long now = System.currentTimeMillis();
        assertTrue(tracker.checkAndRecord("p1", "nonce-1", now));
        assertTrue(tracker.checkAndRecord("p2", "nonce-1", now)); // 不同发送者，相同 nonce
        assertEquals(2, tracker.getTrackedSenderCount());
    }

    @Test
    public void testTimestampOutOfWindowFails() {
        NonceTracker tracker = new NonceTracker(1000, 100); // 1 秒窗口
        long now = System.currentTimeMillis();
        // 过期消息（10 秒前）
        assertFalse(tracker.checkAndRecord("p1", "nonce-1", now - 10000));
        // 未来消息（10 秒后）
        assertFalse(tracker.checkAndRecord("p1", "nonce-2", now + 10000));
    }

    @Test
    public void testTimestampWithinWindowPasses() {
        NonceTracker tracker = new NonceTracker(10000, 100); // 10 秒窗口
        long now = System.currentTimeMillis();
        assertTrue(tracker.checkAndRecord("p1", "nonce-1", now - 5000)); // 5 秒前
        assertTrue(tracker.checkAndRecord("p1", "nonce-2", now + 5000)); // 5 秒后
    }

    @Test
    public void testInvalidWindowZeroThrows() { assertThrows(IllegalArgumentException.class, () -> {
        new NonceTracker(0, 100);
        });
    }

    @Test
    public void testInvalidMaxNoncesZeroThrows() { assertThrows(IllegalArgumentException.class, () -> {
        new NonceTracker(1000, 0);
        });
    }

    @Test
    public void testCleanupAll() {
        NonceTracker tracker = new NonceTracker(1, 100); // 1ms 窗口
        long now = System.currentTimeMillis();
        tracker.checkAndRecord("p1", "nonce-1", now);
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        tracker.cleanupAll();
        // 清理后，相同 nonce 应能再次通过（但时间戳已过期，所以仍 false）
        // 这里仅验证 cleanupAll 不抛异常
    }

    @Test
    public void testGetTrackedSenderCountInitiallyZero() {
        NonceTracker tracker = new NonceTracker();
        assertEquals(0, tracker.getTrackedSenderCount());
    }

    @Test
    public void testDefaultConstructor() {
        NonceTracker tracker = new NonceTracker();
        long now = System.currentTimeMillis();
        assertTrue(tracker.checkAndRecord("p1", "n1", now));
        assertEquals(1, tracker.getTrackedSenderCount());
    }
}