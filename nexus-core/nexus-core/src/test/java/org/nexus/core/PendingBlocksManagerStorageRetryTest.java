package org.nexus.core;

import org.junit.jupiter.api.Test;
import org.nexus.core.validate.CompositeBlockRule;
import org.nexus.core.validate.MerkleRule;
import org.nexus.core.validate.Result;
import org.nexus.db.StateDB;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PG 断开瞬间的区块采纳不能把 @Async 异常抛给默认处理器；失败链应进入内存重试队列，
 * 存储恢复后再次尝试写入。
 */
public class PendingBlocksManagerStorageRetryTest {

    @Test
    void storageFailureQueuesChainAndRetriesAfterRecovery() {
        PendingBlocksManager manager = new PendingBlocksManager();
        StateDB stateDB = mock(StateDB.class);
        NexusChainBlockChain bc = mock(NexusChainBlockChain.class);
        CompositeBlockRule rule = mock(CompositeBlockRule.class);
        MerkleRule merkleRule = mock(MerkleRule.class);

        Block lastConfirmed = block(0);
        when(stateDB.getLastConfirmed()).thenReturn(lastConfirmed);
        when(stateDB.hasBlock(any())).thenReturn(false);
        when(stateDB.hasBlockInCache(any())).thenReturn(false);
        when(bc.hasBlock(any())).thenReturn(false);
        when(rule.validateBlock(any())).thenReturn(Result.SUCCESS);
        when(merkleRule.validateBlock(any())).thenReturn(Result.SUCCESS);
        doThrow(new CannotGetJdbcConnectionException("database down"))
                .doNothing()
                .when(stateDB).writeBlock(any());

        ReflectionTestUtils.setField(manager, "stateDB", stateDB);
        ReflectionTestUtils.setField(manager, "bc", bc);
        ReflectionTestUtils.setField(manager, "rule", rule);
        ReflectionTestUtils.setField(manager, "merkleRule", merkleRule);

        Block candidate = block(1);
        manager.addPendingBlocks(new BlocksCache(candidate));

        Map<String, Block> retryBlocks = retryBlocks(manager);
        assertEquals(1, retryBlocks.size());
        assertTrue(retryBlocks.containsKey(candidate.getHashHexString()));

        manager.retryStorageFailures();

        verify(stateDB, times(2)).writeBlock(candidate);
        assertTrue(retryBlocks.isEmpty());
    }

    @Test
    void accountStoreValidationFailureQueuesChainAndRetriesAfterRecovery() {
        PendingBlocksManager manager = new PendingBlocksManager();
        StateDB stateDB = mock(StateDB.class);
        NexusChainBlockChain bc = mock(NexusChainBlockChain.class);
        CompositeBlockRule rule = mock(CompositeBlockRule.class);
        MerkleRule merkleRule = mock(MerkleRule.class);

        Block lastConfirmed = block(0);
        when(stateDB.getLastConfirmed()).thenReturn(lastConfirmed);
        when(stateDB.hasBlock(any())).thenReturn(false);
        when(stateDB.hasBlockInCache(any())).thenReturn(false);
        when(bc.hasBlock(any())).thenReturn(false);
        when(rule.validateBlock(any()))
                .thenReturn(Result.Error(MerkleRule.ACCOUNT_STORE_UNAVAILABLE_PREFIX + ": database down"))
                .thenReturn(Result.SUCCESS);
        when(merkleRule.validateBlock(any())).thenReturn(Result.SUCCESS);

        ReflectionTestUtils.setField(manager, "stateDB", stateDB);
        ReflectionTestUtils.setField(manager, "bc", bc);
        ReflectionTestUtils.setField(manager, "rule", rule);
        ReflectionTestUtils.setField(manager, "merkleRule", merkleRule);

        Block candidate = block(1);
        manager.addPendingBlocks(new BlocksCache(candidate));

        Map<String, Block> retryBlocks = retryBlocks(manager);
        assertEquals(1, retryBlocks.size());

        manager.retryStorageFailures();

        verify(stateDB).writeBlock(candidate);
        assertTrue(retryBlocks.isEmpty());
    }

    private static Map<String, Block> retryBlocks(PendingBlocksManager manager) {
        return (Map<String, Block>) ReflectionTestUtils.getField(manager, "storageRetryBlocks");
    }

    private static Block block(long height) {
        Block block = new Block();
        block.nVersion = 1;
        block.nHeight = height;
        block.nTime = 1700000000L + height;
        block.hashPrevBlock = new byte[Block.HASH_SIZE];
        block.hashMerkleRoot = new byte[Block.HASH_SIZE];
        block.hashMerkleState = new byte[Block.HASH_SIZE];
        block.hashMerkleIncubate = new byte[Block.HASH_SIZE];
        block.nBits = new byte[Block.HASH_SIZE];
        block.nNonce = new byte[Block.HASH_SIZE];
        return block;
    }
}
