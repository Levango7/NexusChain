package org.nexus.signing.mpc.transport;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link KeyGenRoundMessage} 单元测试。
 */
public class KeyGenRoundMessageTest {

    @Test
    public void testBuilderAndGetters() {
        KeyGenRoundMessage msg = new KeyGenRoundMessage.Builder()
                .round(2)
                .from("p1")
                .to("p2")
                .paillierN("n-hex")
                .paillierG("g-hex")
                .encryptedShare("enc-hex")
                .zkProof("zk")
                .publicKeyShare("pk-share")
                .build();
        assertEquals(2, msg.getRound());
        assertEquals("p1", msg.getFromParticipantId());
        assertEquals("p2", msg.getToParticipantId());
        assertEquals("n-hex", msg.getPaillierNHex());
        assertEquals("g-hex", msg.getPaillierGHex());
        assertEquals("enc-hex", msg.getEncryptedShareHex());
        assertEquals("zk", msg.getZkProofHex());
        assertEquals("pk-share", msg.getPublicKeyShareHex());
    }

    @Test
    public void testPayloadHexRoundTrip() {
        KeyGenRoundMessage original = new KeyGenRoundMessage.Builder()
                .round(4)
                .from("p1")
                .to(null)
                .paillierN("n")
                .paillierG(null)
                .encryptedShare("enc")
                .zkProof("zk")
                .publicKeyShare("pk")
                .build();
        String payload = original.toPayloadHex();
        KeyGenRoundMessage restored = KeyGenRoundMessage.fromPayloadHex(payload);
        assertEquals(original.getRound(), restored.getRound());
        assertEquals(original.getFromParticipantId(), restored.getFromParticipantId());
        assertEquals(original.getToParticipantId(), restored.getToParticipantId());
        assertEquals(original.getPaillierNHex(), restored.getPaillierNHex());
        assertEquals(original.getPaillierGHex(), restored.getPaillierGHex());
        assertEquals(original.getEncryptedShareHex(), restored.getEncryptedShareHex());
        assertEquals(original.getZkProofHex(), restored.getZkProofHex());
        assertEquals(original.getPublicKeyShareHex(), restored.getPublicKeyShareHex());
    }

    @Test
    public void testNullFieldsHandled() {
        KeyGenRoundMessage msg = new KeyGenRoundMessage.Builder()
                .round(1)
                .from(null)
                .build();
        String payload = msg.toPayloadHex();
        KeyGenRoundMessage restored = KeyGenRoundMessage.fromPayloadHex(payload);
        assertNull(restored.getFromParticipantId());
        assertNull(restored.getPaillierNHex());
    }
}