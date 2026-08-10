package org.nexus.signing.mpc.transport;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MpcMessage} 单元测试。
 */
public class MpcMessageTest {

    @Test
    public void testCreateBroadcastMessage() {
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload-hex");
        assertNotNull(msg.getMessageId());
        assertEquals(msg.getSessionId(), "s1");
        assertEquals(1, msg.getRound());
        assertEquals(MpcMessage.Type.SIGN_ROUND, msg.getType());
        assertEquals(msg.getFromParticipantId(), "p1");
        assertEquals(null, msg.getToParticipantId());
        assertEquals(msg.getPayloadHex(), "payload-hex");
        assertTrue(msg.getTimestamp() > 0);
        assertNotNull(msg.getNonce());
        assertEquals(null, msg.getHmacHex());
        assertTrue(msg.isBroadcast());
    }

    @Test
    public void testCreatePointToPointMessage() {
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", "p2", "payload");
        assertFalse(msg.isBroadcast());
        assertEquals(msg.getToParticipantId(), "p2");
    }

    @Test
    public void testWithHmacReturnsCopy() {
        MpcMessage original = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        MpcMessage signed = original.withHmac("hmac-hex");
        assertEquals(original.getMessageId(), signed.getMessageId());
        assertEquals(original.getSessionId(), signed.getSessionId());
        assertEquals(original.getPayloadHex(), signed.getPayloadHex());
        assertEquals(signed.getHmacHex(), "hmac-hex");
        assertEquals(null, original.getHmacHex()); // 原对象不变
    }

    @Test
    public void testSerializeDeserializeRoundTrip() {
        MpcMessage original = MpcMessage.create("s1", 7, MpcMessage.Type.AGGREGATE_ROUND,
                "p1", "p2", "payload-hex").withHmac("hmac-hex");
        byte[] bytes = original.toByteArray();
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        MpcMessage restored = MpcMessage.fromByteArray(bytes);
        assertEquals(original.getMessageId(), restored.getMessageId());
        assertEquals(original.getSessionId(), restored.getSessionId());
        assertEquals(original.getRound(), restored.getRound());
        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getFromParticipantId(), restored.getFromParticipantId());
        assertEquals(original.getToParticipantId(), restored.getToParticipantId());
        assertEquals(original.getPayloadHex(), restored.getPayloadHex());
        assertEquals(original.getTimestamp(), restored.getTimestamp());
        assertEquals(original.getNonce(), restored.getNonce());
        assertEquals(original.getHmacHex(), restored.getHmacHex());
    }

    @Test
    public void testSerializeWithNullFields() {
        MpcMessage msg = MpcMessage.create("s1", 0, MpcMessage.Type.CONTROL,
                "p1", null, null);
        byte[] bytes = msg.toByteArray();
        MpcMessage restored = MpcMessage.fromByteArray(bytes);
        assertEquals(null, restored.getToParticipantId());
        assertEquals(null, restored.getPayloadHex());
    }

    @Test
    public void testEqualsByMessageId() {
        MpcMessage a = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND, "p1", null, "x");
        MpcMessage b = a.withHmac("hmac"); // 同 messageId
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());

        MpcMessage c = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND, "p1", null, "x");
        assertNotEquals(a, c); // 不同 messageId
    }

    @Test
    public void testEqualsSelfAndNull() {
        MpcMessage a = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND, "p1", null, "x");
        assertEquals(a, a);
        assertFalse(a.equals(null));
        assertFalse(a.equals("string"));
    }

    @Test
    public void testInHashSetByMessageId() {
        MpcMessage a = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND, "p1", null, "x");
        Set<MpcMessage> set = new HashSet<>();
        set.add(a);
        set.add(a.withHmac("hmac")); // 同 ID
        assertEquals(1, set.size());
    }

    @Test
    public void testToStringContainsKeyFields() {
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND, "p1", null, "payload");
        String s = msg.toString();
        assertTrue(s.contains("s1"));
        assertTrue(s.contains("p1"));
        assertTrue(s.contains("broadcast"));
    }

    @Test
    public void testAllTypeValues() {
        for (MpcMessage.Type t : MpcMessage.Type.values()) {
            MpcMessage msg = MpcMessage.create("s", 1, t, "p", null, "x");
            assertEquals(t, msg.getType());
        }
    }
}