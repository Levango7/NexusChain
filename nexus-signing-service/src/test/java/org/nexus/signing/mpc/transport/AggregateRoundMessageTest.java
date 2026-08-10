package org.nexus.signing.mpc.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(msg.getFromParticipantId(), "p1");
        assertEquals(msg.getSigShareHex(), "sig-hex");
        assertEquals(msg.getAggregatorId(), "agg-1");
        assertTrue(msg.isWithZkProof());
        assertEquals(msg.getZkProofHex(), "zk-hex");
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