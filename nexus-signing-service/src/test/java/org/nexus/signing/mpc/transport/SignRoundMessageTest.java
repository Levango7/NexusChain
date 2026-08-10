package org.nexus.signing.mpc.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SignRoundMessage} 单元测试。
 */
public class SignRoundMessageTest {

    @Test
    public void testBuilderAndGetter5() {
        SignRoundMessage msg = new SignRoundMessage.Builder()
                .round(3)
                .from("p1")
                .to("p2")
                .rPoint("r-hex")
                .mtaShare("mta-hex")
                .aggregateR("agg-r")
                .rScalar("r-scalar")
                .zkProof("zk")
                .sigShare("sig")
                .build();
        assertEquals(3, msg.getRound());
        assertEquals(msg.getFromParticipantId(), "p1");
        assertEquals(msg.getToParticipantId(), "p2");
        assertEquals(msg.getRPointHex(), "r-hex");
        assertEquals(msg.getMtaShareHex(), "mta-hex");
        assertEquals(msg.getAggregateRHex(), "agg-r");
        assertEquals(msg.getRScalarHex(), "r-scalar");
        assertEquals(msg.getZkProofHex(), "zk");
        assertEquals(msg.getSigShareHex(), "sig");
    }

    @Test
    public void testPayloadHexRoundTrip() {
        SignRoundMessage original = new SignRoundMessage.Builder()
                .round(7)
                .from("p1")
                .to(null)
                .rPoint("r")
                .mtaShare(null)
                .aggregateR("agg")
                .rScalar("rs")
                .zkProof(null)
                .sigShare("ss")
                .build();
        String payload = original.toPayloadHex();
        SignRoundMessage restored = SignRoundMessage.fromPayloadHex(payload);
        assertEquals(original.getRound(), restored.getRound());
        assertEquals(original.getFromParticipantId(), restored.getFromParticipantId());
        assertEquals(original.getToParticipantId(), restored.getToParticipantId());
        assertEquals(original.getRPointHex(), restored.getRPointHex());
        assertEquals(original.getMtaShareHex(), restored.getMtaShareHex());
        assertEquals(original.getAggregateRHex(), restored.getAggregateRHex());
        assertEquals(original.getRScalarHex(), restored.getRScalarHex());
        assertEquals(original.getZkProofHex(), restored.getZkProofHex());
        assertEquals(original.getSigShareHex(), restored.getSigShareHex());
    }

    @Test
    public void testNullFieldsHandledInSerialization() {
        SignRoundMessage msg = new SignRoundMessage.Builder()
                .round(1)
                .from(null)
                .to(null)
                .rPoint(null)
                .build();
        String payload = msg.toPayloadHex();
        SignRoundMessage restored = SignRoundMessage.fromPayloadHex(payload);
        assertNull(restored.getFromParticipantId());
        assertNull(restored.getToParticipantId());
        assertNull(restored.getRPointHex());
    }
}