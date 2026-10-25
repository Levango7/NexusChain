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
    void storageFailurePersistsBlockToOutboxAndClearsItAfterRecovery() {
        // PLAN-019：写库失败时，除了内存队列，还要把"写入意图"落进持久化 outbox；
        // 补写成功后 outbox 行必须被清掉。
        PendingBlocksManager manager = new PendingBlocksManager();
        StateDB stateDB = mock(StateDB.class);
        NexusChainBlockChain bc = mock(NexusChainBlockChain.class);
        CompositeBlockRule rule = mock(CompositeBlockRule.class);
        MerkleRule merkleRule = mock(MerkleRule.class);
        RecordingOutbox outbox = new RecordingOutbox();

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
        ReflectionTestUtils.setField(manager, "blockOutbox", outbox);

        Block candidate = block(1);
        manager.addPendingBlocks(new BlocksCache(candidate));

        assertEquals(1, outbox.enqueued.size(), "写库失败必须把区块记进持久化 outbox");
        assertEquals(candidate.getHashHexString(), outbox.enqueued.get(0).getHashHexString());

        manager.retryStorageFailures();

        assertTrue(outbox.removed.contains(candidate.getHashHexString()),
                "补写成功后必须清掉 outbox 行");
    }

    @Test
    void cachedButUnpersistedBlockKeepsItsOutboxEntry() {
        // PLAN-019：缓存命中不等于已落库（writeBlock 先放缓存再写库）。
        // 这种情况下**不能**清掉 outbox 行，否则写入意图会被悄悄丢掉。
        PendingBlocksManager manager = new PendingBlocksManager();
        StateDB stateDB = mock(StateDB.class);
        NexusChainBlockChain bc = mock(NexusChainBlockChain.class);
        CompositeBlockRule rule = mock(CompositeBlockRule.class);
        MerkleRule merkleRule = mock(MerkleRule.class);
        RecordingOutbox outbox = new RecordingOutbox();

        Block lastConfirmed = block(0);
        when(stateDB.getLastConfirmed()).thenReturn(lastConfirmed);
        when(stateDB.hasBlock(any())).thenReturn(false);   // 没落库
        when(stateDB.hasBlockInCache(any())).thenReturn(true);  // 但缓存里有
        when(bc.hasBlock(any())).thenReturn(false);
        when(rule.validateBlock(any())).thenReturn(Result.SUCCESS);
        when(merkleRule.validateBlock(any())).thenReturn(Result.SUCCESS);

        ReflectionTestUtils.setField(manager, "stateDB", stateDB);
        ReflectionTestUtils.setField(manager, "bc", bc);
        ReflectionTestUtils.setField(manager, "rule", rule);
        ReflectionTestUtils.setField(manager, "merkleRule", merkleRule);
        ReflectionTestUtils.setField(manager, "blockOutbox", outbox);

        Block candidate = block(1);
        manager.addPendingBlocks(new BlocksCache(candidate));

        assertTrue(outbox.removed.isEmpty(), "没落库的区块不得被当成已完成");
    }

    @Test
    void outboxStatusIncludesStagnationSignals() {
        PendingBlocksManager manager = new PendingBlocksManager();
        RecordingOutbox outbox = new RecordingOutbox();
        outbox.oldestCreatedAtValue = 1_700_000_000_000L;
        outbox.maxAttemptsValue = 5;
        ReflectionTestUtils.setField(manager, "blockOutbox", outbox);

        Map<String, Object> status = manager.outboxStatus();

        assertEquals(0, status.get("memoryPending"));
        assertEquals(0, status.get("databasePending"));
        assertEquals(1_700_000_000_000L, status.get("oldestPendingCreatedAt"),
                "滞留信号：最老行创建时刻（PLAN-039 / #29 ②）");
        assertEquals(5, status.get("maxAttempts"), "滞留信号：最大重试次数");
        assertEquals(true, status.get("enabled"));
    }

    @Test
    void outboxStatusHandlesDisabledOutbox() {
        PendingBlocksManager manager = new PendingBlocksManager();

        Map<String, Object> status = manager.outboxStatus();

        assertEquals(-1, status.get("databasePending"), "未装配 → -1（未知）");
        assertEquals(-1L, status.get("oldestPendingCreatedAt"));
        assertEquals(-1, status.get("maxAttempts"));
        assertEquals(false, status.get("enabled"));
    }

    /** 记录入队/移除的假 outbox。 */
    private static final class RecordingOutbox implements org.nexus.core.pending.PendingBlockOutbox {
        private final java.util.List<Block> enqueued = new java.util.ArrayList<>();
        private final java.util.List<String> removed = new java.util.ArrayList<>();
        private long oldestCreatedAtValue = -1L;
        private int maxAttemptsValue = -1;

        @Override
        public void enqueue(Block block) {
            enqueued.add(block);
        }

        @Override
        public void remove(Block block) {
            removed.add(block.getHashHexString());
        }

        @Override
        public java.util.List<Block> loadPending(int limit) {
            return java.util.List.of();
        }

        @Override
        public int pendingCount() {
            return enqueued.size() - removed.size();
        }

        @Override
        public long oldestCreatedAt() {
            return oldestCreatedAtValue;
        }

        @Override
        public int maxAttempts() {
            return maxAttemptsValue;
        }
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
