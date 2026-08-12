package org.nexus.consensus.finality.net;

import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.Vote;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2P 投票载荷编解码器测试（ADR-030 M_net）。
 */
class FinalityVoteP2PCodecTest {

    private static final byte[] CP = new byte[]{1, 2, 3, 4};

    @Test
    void isVotePayloadDetectsMagicPrefix() {
        Vote vote = new Vote(3, CP, "validator-x", new byte[]{0x01});
        byte[] payload = FinalityVoteCodec.encode(vote);
        assertTrue(FinalityVoteP2PCodec.isVotePayload(payload));
        assertEquals(FinalityVoteP2PCodec.MAGIC, payload[0]);
    }

    @Test
    void isVotePayloadRejectsNonMagic() {
        assertFalse(FinalityVoteP2PCodec.isVotePayload(null));
        assertFalse(FinalityVoteP2PCodec.isVotePayload(new byte[0]));
        assertFalse(FinalityVoteP2PCodec.isVotePayload(new byte[]{0x01, 0x02}));
        assertFalse(FinalityVoteP2PCodec.isVotePayload(new byte[]{0x00, 0x5A}));
    }

    @Test
    void decodeRoundTrip() {
        Vote vote = new Vote(7, CP, "validator-y", new byte[]{0x10, 0x20, 0x30});
        byte[] payload = FinalityVoteCodec.encode(vote);
        Vote decoded = FinalityVoteP2PCodec.decode(payload);
        assertNotNull(decoded);
        assertEquals(7, decoded.getEpoch());
        assertArrayEquals(CP, decoded.getCheckpointHash());
        assertEquals("validator-y", decoded.getValidatorAddress());
        assertArrayEquals(new byte[]{0x10, 0x20, 0x30}, decoded.getSignature());
    }

    @Test
    void decodeRejectsPlainPayload() {
        assertNull(FinalityVoteP2PCodec.decode(new byte[]{0x01, 0x02, 0x03}));
        assertNull(FinalityVoteP2PCodec.decode(null));
    }

    @Test
    void decodeEmptySignatureRoundTrip() {
        Vote vote = new Vote(1, CP, "v", new byte[0]);
        byte[] payload = FinalityVoteCodec.encode(vote);
        Vote decoded = FinalityVoteP2PCodec.decode(payload);
        assertNotNull(decoded);
        assertArrayEquals(new byte[0], decoded.getSignature());
    }
}