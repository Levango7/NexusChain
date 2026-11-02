package org.nexus.consensus.finality.net;

import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.FinalityRejectionCounter;
import org.nexus.consensus.finality.FinalityRejectionCounter.FinalizeReason;
import org.nexus.consensus.finality.FinalityRejectionCounter.Phase;
import org.nexus.consensus.finality.FinalityRejectionCounter.Reason;
import org.nexus.consensus.finality.Vote;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.ValidatorRegistry;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-067：最终性网络载荷的尺寸上界。
 *
 * <p>P2P 允许近 4MB 的单帧；最终性投票正常只有几百字节。没有上限时，
 * 一张带 2MB 检查点哈希的票会在解析阶段被 hex 成 4MB 字符串。这里钉住
 * codec 与 broadcaster 两道闸，并确认超长帧不会被误记成 malformed。</p>
 */
class FinalityVotePayloadBoundsTest {

    @Test
    void codecRejectsEncodedPayloadAboveLimit() {
        byte[] hugeHash = new byte[FinalityVoteCodec.MAX_ENCODED_VOTE_BYTES];
        Vote vote = new Vote(1, hugeHash, "validator-x", new byte[64], new byte[32]);

        byte[] encoded = FinalityVoteCodec.encode(vote);

        assertTrue(encoded.length > FinalityVoteCodec.MAX_ENCODED_VOTE_BYTES,
                "用例前提：编码后必须确实超过上限");
        assertNull(FinalityVoteCodec.decode(encoded),
                "超限载荷必须在解析前被拒绝，不能进入 JSON 解析");
    }

    @Test
    void codecStillAcceptsNormalVote() {
        byte[] checkpoint = new byte[32];
        for (int i = 0; i < checkpoint.length; i++) {
            checkpoint[i] = (byte) i;
        }
        Vote vote = new Vote(7, checkpoint, "validator-y", new byte[64], new byte[32]);

        byte[] encoded = FinalityVoteCodec.encode(vote);
        Vote decoded = FinalityVoteCodec.decode(encoded);

        assertTrue(encoded.length <= FinalityVoteCodec.MAX_ENCODED_VOTE_BYTES,
                "正常票必须远低于上限");
        assertNotNull(decoded, "正常票不得被尺寸闸误杀");
        assertArrayEquals(checkpoint, decoded.getCheckpointHash(),
                "解码后的检查点哈希必须与编码前一致");
    }

    @Test
    void broadcasterDropsOversizedPayloadAndCountsIt() {
        RecordingCounter counter = new RecordingCounter();
        FinalityVoteBroadcaster broadcaster = newBroadcaster();
        broadcaster.setRejectionCounter(counter);

        byte[] hugeHash = new byte[FinalityVoteCodec.MAX_ENCODED_VOTE_BYTES];
        byte[] oversized = FinalityVoteCodec.encode(
                new Vote(1, hugeHash, "validator-x", new byte[64], new byte[32]));

        broadcaster.onVoteReceived(oversized);

        assertEquals(1, counter.count(Reason.PAYLOAD_TOO_LARGE, Phase.ADMISSION),
                "超长帧必须在解析前被计数");
        assertEquals(0, counter.count(Reason.MALFORMED_VOTE, Phase.ADMISSION),
                "超长帧不得被误记成 malformed_vote");
    }

    @Test
    void broadcasterCountsMalformedPayload() {
        RecordingCounter counter = new RecordingCounter();
        FinalityVoteBroadcaster broadcaster = newBroadcaster();
        broadcaster.setRejectionCounter(counter);

        broadcaster.onVoteReceived(new byte[]{0x00, 0x01});

        assertEquals(1, counter.count(Reason.MALFORMED_VOTE, Phase.ADMISSION),
                "魔数错误的短帧必须计入 malformed_vote");
        assertEquals(0, counter.count(Reason.PAYLOAD_TOO_LARGE, Phase.ADMISSION),
                "短帧不得被记成超长");
    }

    private static FinalityVoteBroadcaster newBroadcaster() {
        ValidatorRegistry registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        StakingServiceImpl staking = new StakingServiceImpl();
        FinalityGadget gadget = new FinalityGadget(registry, staking);
        return new FinalityVoteBroadcaster(gadget, event -> { });
    }

    /** 记录型假端口：只存 (reason, phase) → 次数。 */
    private static final class RecordingCounter implements FinalityRejectionCounter {

        private final Map<String, Integer> votes = new HashMap<>();

        int count(Reason reason, Phase phase) {
            return votes.getOrDefault(reason + "@" + phase, 0);
        }

        @Override
        public void countVoteRejected(Reason reason, Phase phase) {
            votes.merge(reason + "@" + phase, 1, Integer::sum);
        }

        @Override
        public void countFinalizeRejected(FinalizeReason reason) {
            // 本用例不覆盖 finalize 路径。
        }
    }
}
