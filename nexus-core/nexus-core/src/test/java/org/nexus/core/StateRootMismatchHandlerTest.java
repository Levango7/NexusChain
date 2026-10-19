package org.nexus.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.core.account.AccountDB;
import org.nexus.core.event.StateRootMismatchEvent;
import org.nexus.core.incubator.IncubatorDB;
import org.nexus.db.StateDB;
import org.nexus.p2p.Peer;
import org.nexus.p2p.PeersStorage;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PLAN-014 Phase 2 回归：状态根不一致处置。
 *
 * <p>覆盖：observe 默认位不处置 / 未知动作回落 observe / rollback 回滚状态并拉黑相关对端 /
 * 最终化护栏拒绝回滚（ADR-030）/ 已回滚或已推进时幂等跳过 / epoch 长度使用配置值而非硬编码 32。</p>
 */
class StateRootMismatchHandlerTest {

    private StateDB stateDB;
    private AccountDB accountDB;
    private IncubatorDB incubatorDB;
    private BlockOriginTracker tracker;
    private PeersStorage peersStorage;
    private FinalityGadget finalityGadget;
    private StateRootMismatchHandler handler;

    @BeforeEach
    void setUp() {
        stateDB = mock(StateDB.class);
        accountDB = mock(AccountDB.class);
        incubatorDB = mock(IncubatorDB.class);
        tracker = mock(BlockOriginTracker.class);
        peersStorage = mock(PeersStorage.class);
        finalityGadget = mock(FinalityGadget.class);

        // 真实实现为获取 StateDB 写锁；单测中直接执行动作
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(stateDB).executeUnderWriteLock(any(Runnable.class));
        when(accountDB.deleteFromHeight(anyLong())).thenReturn(2);
        when(incubatorDB.deleteFromHeight(anyLong())).thenReturn(1);
        when(tracker.peersAboveHeight(anyLong())).thenReturn(Set.of());

        handler = new StateRootMismatchHandler();
        ReflectionTestUtils.setField(handler, "stateDB", stateDB);
        ReflectionTestUtils.setField(handler, "accountDB", accountDB);
        ReflectionTestUtils.setField(handler, "incubatorDB", incubatorDB);
        ReflectionTestUtils.setField(handler, "blockOriginTracker", tracker);
        ReflectionTestUtils.setField(handler, "peersStorage", peersStorage);
        ReflectionTestUtils.setField(handler, "finalityGadget", finalityGadget);
        ReflectionTestUtils.setField(handler, "action", "observe");
        ReflectionTestUtils.setField(handler, "epochLength", 32L);
    }

    @Test
    void observeModeOnlyCountsAndNeverEnforces() {
        handler.onStateRootMismatch(event(8));

        assertEquals(1, handler.getMismatchEvents());
        assertEquals(0, handler.getRollbacksExecuted());
        assertEquals(0, handler.getBlacklistedPeers());
        verify(stateDB, never()).rollbackTo(anyLong());
        verify(accountDB, never()).deleteFromHeight(anyLong());
        verify(incubatorDB, never()).deleteFromHeight(anyLong());
        verify(peersStorage, never()).blockPeer(any());
        verify(stateDB, never()).executeUnderWriteLock(any(Runnable.class));
    }

    @Test
    void unknownActionFallsBackToObserve() {
        ReflectionTestUtils.setField(handler, "action", "whatever");

        handler.onStateRootMismatch(event(8));

        assertEquals(0, handler.getRollbacksExecuted());
        verify(stateDB, never()).rollbackTo(anyLong());
    }

    @Test
    void rollbackModeRollsBackStateAndBlacklistsRelatedPeers() {
        ReflectionTestUtils.setField(handler, "action", "rollback");
        when(stateDB.getBestBlock()).thenReturn(block(10));
        when(stateDB.rollbackTo(7)).thenReturn(3);
        Peer p1 = mock(Peer.class);
        Peer p2 = mock(Peer.class);
        when(tracker.peersAboveHeight(7)).thenReturn(new HashSet<>(Arrays.asList(p1, p2)));

        handler.onStateRootMismatch(event(8));

        verify(stateDB).executeUnderWriteLock(any(Runnable.class));
        verify(stateDB).rollbackTo(7);
        verify(accountDB).deleteFromHeight(8);
        verify(incubatorDB).deleteFromHeight(8);
        verify(peersStorage).blockPeer(p1);
        verify(peersStorage).blockPeer(p2);
        assertEquals(1, handler.getRollbacksExecuted());
        assertEquals(2, handler.getBlacklistedPeers());
    }

    @Test
    void finalityGuardRejectsRollbackOfFinalizedBlock() {
        ReflectionTestUtils.setField(handler, "action", "rollback");
        when(stateDB.getBestBlock()).thenReturn(block(10));
        when(finalityGadget.isFinalized(anyLong(), any())).thenReturn(true);

        handler.onStateRootMismatch(event(8));

        assertEquals(1, handler.getFinalityRejections());
        assertEquals(0, handler.getRollbacksExecuted());
        verify(stateDB, never()).rollbackTo(anyLong());
        verify(peersStorage, never()).blockPeer(any());
    }

    @Test
    void skipsWhenAlreadyRolledBack() {
        ReflectionTestUtils.setField(handler, "action", "rollback");
        when(stateDB.getBestBlock()).thenReturn(block(7));

        handler.onStateRootMismatch(event(8));

        assertEquals(0, handler.getRollbacksExecuted());
        verify(stateDB, never()).rollbackTo(anyLong());
    }

    @Test
    void usesConfiguredEpochLengthForFinalityCheck() {
        ReflectionTestUtils.setField(handler, "action", "rollback");
        ReflectionTestUtils.setField(handler, "epochLength", 4L);
        when(stateDB.getBestBlock()).thenReturn(block(10));
        when(stateDB.rollbackTo(7)).thenReturn(3);

        handler.onStateRootMismatch(event(8));

        // height=8，epochLength=4 → epoch = (8-1)/4+1 = 2（硬编码 32 会得到 1）
        verify(finalityGadget).isFinalized(eq(2L), any());
    }

    private StateRootMismatchEvent event(long height) {
        return new StateRootMismatchEvent(this, block(height), "merkle state validate fail");
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
