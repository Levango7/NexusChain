package org.nexus.signing.mpc.transport;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link AggregateRoundMessage} 单元测试。
 */
public class AggregateRoundMessageTest {

    @Test
    public void testBuilderAndGetters() {
        AggregateRoundMessage msg = new AggregateRoundMessage.Builder()
                .from("p1")
                .sigShare("sig-hex")
                .aggregator("agg-1")
                .withZkProof(true)
                .zkProof("zk-hex")
                .build();
        assertEquals("p1", msg.getFromParticipantId());
        assertEquals("sig-hex", msg.getSigShareHex());
        assertEquals("agg-1", msg.getAggregatorId());
        assertTrue(msg.isWithZkProof());
        assertEquals("zk-hex", msg.getZkProofHex());
    }

    @Test
    public void testPayloadHexRoundTrip() {
        AggregateRoundMessage original = new AggregateRoundMessage.Builder()
                .from("p1")
                .sigShare("sig")
                .aggregator(null)
                .withZkProof(false)
                .zkProof(null)
                .build();
        String payload = original.toPayloadHex();
        AggregateRoundMessage restored = AggregateRoundMessage.fromPayloadHex(payload);
        assertEquals(original.getFromParticipantId(), restored.getFromParticipantId());
        assertEquals(original.getSigShareHex(), restored.getSigShareHex());
        assertEquals(original.getAggregatorId(), restored.getAggregatorId());
        assertEquals(original.isWithZkProof(), restored.isWithZkProof());
        assertEquals(original.getZkProofHex(), restored.getZkProofHex());
    }

    @Test
    public void testWithZkProofFlagSerialization() {
        AggregateRoundMessage withZk = new AggregateRoundMessage.Builder()
                .from("p1").sigShare("s").withZkProof(true).zkProof("zk").build();
        AggregateRoundMessage withoutZk = new AggregateRoundMessage.Builder()
                .from("p1").sigShare("s").withZkProof(false).build();

        assertTrue(AggregateRoundMessage.fromPayloadHex(withZk.toPayloadHex()).isWithZkProof());
        assertFalse(AggregateRoundMessage.fromPayloadHex(withoutZk.toPayloadHex()).isWithZkProof());
    }

    @Test
    public void testNullFromHandled() {
        AggregateRoundMessage msg = new AggregateRoundMessage.Builder()
                .from(null).sigShare("s").build();
        AggregateRoundMessage restored = AggregateRoundMessage.fromPayloadHex(msg.toPayloadHex());
        assertNull(restored.getFromParticipantId());
    }
}