package org.nexus.consensus.finality.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-025：补票请求载荷编解码器。
 *
 * <p>钉死的事：魔数与投票（0x5A）、验证人集（0x56）不冲突；from/max 有边界收敛；
 * 损坏载荷返回 null 而不抛异常（网络输入必须可丢弃）。</p>
 */
class FinalityVoteSyncCodecTest {

    @Test
    void roundTripKeepsValues() {
        byte[] payload = FinalityVoteSyncCodec.encode(42L, 7);
        assertTrue(FinalityVoteSyncCodec.isSyncPayload(payload));

        FinalityVoteSyncCodec.SyncRequest req = FinalityVoteSyncCodec.decode(payload);
        assertEquals(42L, req.fromEpoch());
        assertEquals(7, req.maxVotes());
    }

    @Test
    void magicsDoNotCollide() {
        assertEquals((byte) 0x5B, FinalityVoteSyncCodec.MAGIC);
        assertTrue(FinalityVoteSyncCodec.MAGIC != FinalityVoteCodec.MAGIC,
                "补票请求魔数不得与投票魔数相同");
        assertFalse(FinalityVoteSyncCodec.isSyncPayload(new byte[]{FinalityVoteCodec.MAGIC, '{', '}'}),
                "投票载荷不得被识别为补票请求");
        assertFalse(FinalityVoteSyncCodec.isSyncPayload(null));
        assertFalse(FinalityVoteSyncCodec.isSyncPayload(new byte[]{FinalityVoteSyncCodec.MAGIC}));
    }

    @Test
    void negativeFromIsClampedToZero() {
        FinalityVoteSyncCodec.SyncRequest req =
                FinalityVoteSyncCodec.decode(FinalityVoteSyncCodec.encode(-5L, 0));
        assertEquals(0L, req.fromEpoch());
        assertEquals(FinalityVoteSyncCodec.DEFAULT_MAX_VOTES, req.maxVotes(),
                "max<=0 应回落到默认上限");
    }

    @Test
    void absurdMaxIsClampedToDefault() {
        FinalityVoteSyncCodec.SyncRequest req =
                FinalityVoteSyncCodec.decode(FinalityVoteSyncCodec.encode(1L, 999_999));
        assertEquals(FinalityVoteSyncCodec.DEFAULT_MAX_VOTES, req.maxVotes(),
                "过大的 max 必须被夹住，避免一次请求拉回全量历史");
    }

    @Test
    void malformedPayloadReturnsNull() {
        assertNull(FinalityVoteSyncCodec.decode(new byte[]{FinalityVoteSyncCodec.MAGIC, 'x'}));
        assertNull(FinalityVoteSyncCodec.decode(null));
        assertNull(FinalityVoteSyncCodec.decode(new byte[]{0x5A, 1, 2, 3}));
    }
}
