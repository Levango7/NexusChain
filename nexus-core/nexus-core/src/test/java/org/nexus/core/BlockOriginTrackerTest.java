package org.nexus.core;

import org.junit.jupiter.api.Test;
import org.nexus.p2p.Peer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * PLAN-014 Phase 2 回归：区块来源追踪（回滚/拉黑阶段的 区块 → 对端 归因）。
 */
class BlockOriginTrackerTest {

    @Test
    void recordsAndQueriesPeersAboveHeight() {
        BlockOriginTracker tracker = new BlockOriginTracker();
        Peer p1 = mock(Peer.class);
        Peer p2 = mock(Peer.class);

        tracker.record("aa", 5, p1);
        tracker.record("bb", 8, p2);
        tracker.record("cc", 8, p1);

        assertEquals(3, tracker.size());
        assertEquals(Set.of(p1, p2), tracker.peersAboveHeight(4));
        assertEquals(Set.of(p1, p2), tracker.peersAboveHeight(5), "边界语义：高度严格大于");
        assertEquals(Set.of(), tracker.peersAboveHeight(8));
    }

    @Test
    void ignoresNullInputs() {
        BlockOriginTracker tracker = new BlockOriginTracker();
        Peer p1 = mock(Peer.class);

        tracker.record((Block) null, p1);
        tracker.record("aa", 5, null);
        tracker.record((String) null, 5, p1);

        assertEquals(0, tracker.size());
        assertEquals(Set.of(), tracker.peersAboveHeight(0));
    }

    @Test
    void recordsBlockHashAndHeight() {
        BlockOriginTracker tracker = new BlockOriginTracker();
        Peer p1 = mock(Peer.class);
        Block block = block(9);

        tracker.record(block, p1);

        assertEquals(1, tracker.size());
        assertEquals(Set.of(p1), tracker.peersAboveHeight(8));
        assertEquals(Set.of(), tracker.peersAboveHeight(9));
    }

    @Test
    void staysWithinBoundedCapacity() {
        BlockOriginTracker tracker = new BlockOriginTracker();
        Peer p1 = mock(Peer.class);

        for (int i = 0; i < BlockOriginTracker.CAPACITY + 500; i++) {
            tracker.record("k" + i, 1, p1);
        }

        assertTrue(tracker.size() <= BlockOriginTracker.CAPACITY,
                "来源追踪必须有界（LRU），实际=" + tracker.size());
    }

    private static Block block(long height) {
        Block b = new Block();
        b.nVersion = 1;
        b.nHeight = height;
        b.nTime = 1_700_000_000_000L;
        b.hashPrevBlock = filled(Block.HASH_SIZE, (byte) 0x01);
        b.hashMerkleRoot = filled(Block.HASH_SIZE, (byte) 0x02);
        b.hashMerkleState = filled(Block.HASH_SIZE, (byte) 0x03);
        b.hashMerkleIncubate = filled(Block.HASH_SIZE, (byte) 0x04);
        b.nBits = filled(Block.HASH_SIZE, (byte) 0x05);
        b.nNonce = filled(Block.HASH_SIZE, (byte) 0x06);
        b.body = new ArrayList<>();
        return b;
    }

    private static byte[] filled(int size, byte value) {
        byte[] out = new byte[size];
        Arrays.fill(out, value);
        return out;
    }
}
