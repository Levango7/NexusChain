package org.nexus.consensus.finality.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证人集合广播消息编解码测试（PLAN-001 步骤 1）。
 */
class ValidatorSetCodecTest {

    @Test
    void encodeAddRoundTrip() {
        byte[] payload = ValidatorSetCodec.encodeAdd("1AddrABC123", "aa11bb22cc33", "1000");
        assertTrue(ValidatorSetCodec.isValidatorSetPayload(payload));
        assertEquals(ValidatorSetCodec.MAGIC, payload[0]);

        ValidatorSetCodec.ValidatorSetMessage msg = ValidatorSetCodec.decode(payload);
        assertNotNull(msg);
        assertTrue(msg.isAdd());
        assertEquals("1AddrABC123", msg.address());
        assertEquals("aa11bb22cc33", msg.publicKey());
        assertEquals("1000", msg.stakeAmount());
    }

    @Test
    void encodeRemoveRoundTrip() {
        byte[] payload = ValidatorSetCodec.encodeRemove("1AddrXYZ999");
        ValidatorSetCodec.ValidatorSetMessage msg = ValidatorSetCodec.decode(payload);
        assertNotNull(msg);
        assertFalse(msg.isAdd());
        assertEquals("1AddrXYZ999", msg.address());
        assertNull(msg.publicKey());
        assertNull(msg.stakeAmount());
    }

    @Test
    void rejectsNonValidatorPayload() {
        assertFalse(ValidatorSetCodec.isValidatorSetPayload(null));
        assertFalse(ValidatorSetCodec.isValidatorSetPayload(new byte[0]));
        assertFalse(ValidatorSetCodec.isValidatorSetPayload(new byte[]{0x01, 0x02}));
        // 投票消息（魔数 0x5A）不应被识别为 validator-set
        org.nexus.consensus.finality.Vote vote =
                new org.nexus.consensus.finality.Vote(1, new byte[]{1}, "v", new byte[0]);
        byte[] votePayload = FinalityVoteCodec.encode(vote);
        assertFalse(ValidatorSetCodec.isValidatorSetPayload(votePayload));
    }

    @Test
    void rejectsMalformed() {
        assertNull(ValidatorSetCodec.decode(new byte[]{ValidatorSetCodec.MAGIC}));  // 无 body
        assertNull(ValidatorSetCodec.decode(null));
    }
}
