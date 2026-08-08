package org.nexus.signing.mpc.router;

import org.nexus.signing.mpc.transport.MpcMessage;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link MessageDeduplicator} 单元测试。
 */
public class MessageDeduplicatorTest {

    private MessageDeduplicator deduplicator;

    @Before
    public void setUp() {
        deduplicator = new MessageDeduplicator();
    }

    @Test
    public void testNewMessageReturnsTrue() {
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        assertTrue(deduplicator.checkAndRecord(msg));
        assertEquals(1, deduplicator.size());
    }

    @Test
    public void testDuplicateMessageReturnsFalse() {
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        assertTrue(deduplicator.checkAndRecord(msg));
        assertFalse(deduplicator.checkAndRecord(msg));
        assertEquals(1, deduplicator.size());
    }

    @Test
    public void testCheckAndRecordByMessageId() {
        assertTrue(deduplicator.checkAndRecord("id-1"));
        assertFalse(deduplicator.checkAndRecord("id-1"));
        assertTrue(deduplicator.checkAndRecord("id-2"));
        assertEquals(2, deduplicator.size());
    }

    @Test
    public void testClearResetsState() {
        deduplicator.checkAndRecord("id-1");
        deduplicator.checkAndRecord("id-2");
        assertEquals(2, deduplicator.size());
        deduplicator.clear();
        assertEquals(0, deduplicator.size());
        // 清空后相同 ID 应再次被视为新
        assertTrue(deduplicator.checkAndRecord("id-1"));
    }

    @Test
    public void testCapacityEviction() {
        MessageDeduplicator small = new MessageDeduplicator(2);
        small.checkAndRecord("id-1");
        small.checkAndRecord("id-2");
        small.checkAndRecord("id-3"); // 触发驱逐最旧的 id-1
        assertEquals(2, small.size());
        // id-1 已被驱逐，应再次被视为新
        assertTrue(small.checkAndRecord("id-1"));
    }

    @Test
    public void testDefaultCapacityConstructor() {
        MessageDeduplicator d = new MessageDeduplicator();
        // 不抛异常
        d.checkAndRecord("x");
        assertEquals(1, d.size());
    }
}