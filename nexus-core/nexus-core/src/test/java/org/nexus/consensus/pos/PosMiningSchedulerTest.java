package org.nexus.consensus.pos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.pow.ConsensusConfig;
import org.nexus.core.Block;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link PosMiningScheduler} 调度器测试。
 */
public class PosMiningSchedulerTest {

    private PosMiningScheduler scheduler;
    private PosConsensus posConsensus;
    private ConsensusConfig consensusConfig;

    @BeforeEach
    public void setUp() {
        posConsensus = mock(PosConsensus.class);
        consensusConfig = mock(ConsensusConfig.class);
        scheduler = new PosMiningScheduler(posConsensus, consensusConfig);
    }

    @Test
    public void testTryProposeNotPosMode() {
        when(consensusConfig.isPosMode()).thenReturn(false);
        scheduler.tryPropose();
        verifyNoInteractions(posConsensus);
    }

    @Test
    public void testTryProposeMiningDisabled() {
        when(consensusConfig.isPosMode()).thenReturn(true);
        when(consensusConfig.isEnableMining()).thenReturn(false);
        scheduler.tryPropose();
        verifyNoInteractions(posConsensus);
    }

    @Test
    public void testTryProposeSuccess() {
        when(consensusConfig.isPosMode()).thenReturn(true);
        when(consensusConfig.isEnableMining()).thenReturn(true);
        Block block = mock(Block.class);
        block.nHeight = 100;
        when(posConsensus.propose()).thenReturn(block);

        scheduler.tryPropose();
        verify(posConsensus).propose();
    }

    @Test
    public void testTryProposeReturnsNull() {
        when(consensusConfig.isPosMode()).thenReturn(true);
        when(consensusConfig.isEnableMining()).thenReturn(true);
        when(posConsensus.propose()).thenReturn(null);

        scheduler.tryPropose();
        verify(posConsensus).propose();
    }

    @Test
    public void testTryProposeException() {
        when(consensusConfig.isPosMode()).thenReturn(true);
        when(consensusConfig.isEnableMining()).thenReturn(true);
        when(posConsensus.propose()).thenThrow(new RuntimeException("test error"));

        // 不应抛出异常
        scheduler.tryPropose();
        verify(posConsensus).propose();
    }
}