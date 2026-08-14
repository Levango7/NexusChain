package org.nexus.core;

import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.db.StateDB;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PLAN-003 ReorgManager 单测：分叉检测/受控切换/最终化护栏/孤儿拒绝。
 */
class ReorgManagerTest {

    /** 构造区块：height 指定高度，prevMarker 指定父块 hash 首字节（32 字节数组）。 */
    private Block block(long height, int prevMarker) {
        Block b = new Block();
        b.nHeight = height;
        byte[] prev = new byte[32];
        prev[0] = (byte) prevMarker;
        b.hashPrevBlock = prev;
        b.body = new java.util.ArrayList<>();
        return b;
    }

    @Test
    void skipsWhenTipNotHigherThanLocalBest() {
        StateDB stateDB = mock(StateDB.class);
        Block localBest = block(10, 0);
        when(stateDB.getBestBlock()).thenReturn(localBest);

        ReorgManager reorg = new ReorgManager(stateDB, null);
        assertFalse(reorg.handlePotentialFork(block(10, 0)),
                "同高度不分叉");
        assertFalse(reorg.handlePotentialFork(block(5, 0)),
                "更低高度不分叉");
        verify(stateDB, never()).rollbackTo(anyLong());
    }

    @Test
    void switchesToLongerConnectedFork() {
        StateDB stateDB = mock(StateDB.class);
        // 本地 best=10；tip=12，分叉链 11 → 本地 10（分叉点）
        Block localBest = block(10, 10);
        when(stateDB.getBestBlock()).thenReturn(localBest);
        when(stateDB.getBlock(any())).thenAnswer(inv -> {
            byte[] h = inv.getArgument(0);
            if (h == null || h.length != 32) return null;
            long marker = h[0] & 0xFF;
            if (marker == 11) {
                return block(11, 10);  // 分叉链 11，父=本地 10
            }
            if (marker == 10) {
                return block(10, 10);  // 本地主链 10（分叉点）
            }
            return null;
        });
        when(stateDB.rollbackTo(anyLong())).thenReturn(1);

        ReorgManager reorg = new ReorgManager(stateDB, null);
        Block tip = block(12, 11);  // tip 12，父块 marker=11 → 分叉链 11
        assertTrue(reorg.handlePotentialFork(tip), "更长且可回溯的分叉链应切换");
        verify(stateDB).rollbackTo(anyLong());
        verify(stateDB, atLeastOnce()).writeBlock(any());
    }

    @Test
    void rejectsOrphanNotConnectedToLocalChain() {
        StateDB stateDB = mock(StateDB.class);
        Block localBest = block(10, 0);
        when(stateDB.getBestBlock()).thenReturn(localBest);
        when(stateDB.getBlock(any())).thenReturn(null);  // 父链无法回溯

        ReorgManager reorg = new ReorgManager(stateDB, null);
        assertFalse(reorg.handlePotentialFork(block(20, 19)),
                "无法回溯到本地链的孤儿块不切换");
        verify(stateDB, never()).rollbackTo(anyLong());
    }

    @Test
    void finalityGuardRejectsSwitch() {
        StateDB stateDB = mock(StateDB.class);
        Block localBest = block(10, 10);
        when(stateDB.getBestBlock()).thenReturn(localBest);

        // 与 switches 相同回溯：分叉链 11 → 本地 10（分叉点）
        when(stateDB.getBlock(any())).thenAnswer(inv -> {
            byte[] h = inv.getArgument(0);
            if (h == null || h.length != 32) return null;
            long marker = h[0] & 0xFF;
            if (marker == 11) {
                return block(11, 10);
            }
            if (marker == 10) {
                return block(10, 10);
            }
            return null;
        });

        // 分叉点最终化 → 护栏禁止切换
        FinalityGadget gadget = mock(FinalityGadget.class);
        when(gadget.isFinalized(anyLong(), any())).thenReturn(true);

        ReorgManager reorg = new ReorgManager(stateDB, gadget);
        Block tip = block(12, 11);
        assertFalse(reorg.handlePotentialFork(tip),
                "已最终化分叉点必须拒绝切换（护栏）");
        verify(stateDB, never()).rollbackTo(anyLong());
        verify(stateDB, never()).writeBlock(any());
    }
}
