package org.nexus.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.core.validate.CompositeBlockRule;
import org.nexus.core.validate.MerkleRule;
import org.nexus.core.validate.Result;
import org.nexus.db.StateDB;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * unknown-proposer 挂起的弃权风险可观测性（PLAN-051）。
 *
 * <p>锁定：① 同一高度被 {@code unknown proposer} 拒绝时，defer 计数按高度累计；
 * ② 计数达到阈值（5）后仍返回 false（defer 语义不变），但计数本身可被端点/
 * 运维读取；③ 阈值以下/以上都不抛异常（@Async 安全）。</p>
 *
 * <p>口径：本测试只验证"计数器与阈值语义"，不验证日志级别（由调用方
 * logger 负责）；日志断言在测试床复现时人工核对 {@code abstain-risk} 行。</p>
 */
public class PendingBlocksManagerAbstainObservabilityTest {

    private PendingBlocksManager manager;
    private StateDB stateDB;
    private CompositeBlockRule rule;

    @BeforeEach
    void setUp() {
        manager = new PendingBlocksManager();
        stateDB = mock(StateDB.class);
        NexusChainBlockChain bc = mock(NexusChainBlockChain.class);
        rule = mock(CompositeBlockRule.class);
        MerkleRule merkleRule = mock(MerkleRule.class);

        when(stateDB.getLastConfirmed()).thenReturn(block(0));
        when(stateDB.hasBlock(any())).thenReturn(false);
        when(stateDB.hasBlockInCache(any())).thenReturn(false);
        when(bc.hasBlock(any())).thenReturn(false);
        when(rule.validateBlock(any())).thenReturn(
                Result.Error("unknown proposer (validator set not synchronized) at height 125"));

        ReflectionTestUtils.setField(manager, "stateDB", stateDB);
        ReflectionTestUtils.setField(manager, "bc", bc);
        ReflectionTestUtils.setField(manager, "rule", rule);
        ReflectionTestUtils.setField(manager, "merkleRule", merkleRule);
    }

    @Test
    void deferCountAccumulatesPerHeight() {
        Block candidate = block(125);
        for (int i = 0; i < 3; i++) {
            manager.addPendingBlocks(new BlocksCache(candidate));
        }
        assertEquals(3, deferCount(manager).get(125L).intValue(),
                "同一高度三次 unknown-proposer 拒绝应累计为 3");
    }

    @Test
    void thresholdCrossingStillDefersWithoutThrowing() {
        Block candidate = block(125);
        for (int i = 0; i < 7; i++) {
            // 每次都不抛异常（defer 语义保持：返回 false，等待验证人集同步）
            manager.addPendingBlocks(new BlocksCache(candidate));
        }
        assertEquals(7, deferCount(manager).get(125L).intValue(),
                "超过阈值后继续累计（运维可见持续弃权）");
    }

    @Test
    void differentHeightsAreCountedSeparately() {
        manager.addPendingBlocks(new BlocksCache(block(125)));
        manager.addPendingBlocks(new BlocksCache(block(126)));
        assertEquals(1, deferCount(manager).get(125L).intValue());
        assertEquals(1, deferCount(manager).get(126L).intValue(),
                "不同高度独立计数（不能把 A 高度的挂起算到 B 高度）");
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Long, Integer> deferCount(PendingBlocksManager manager) {
        return (ConcurrentHashMap<Long, Integer>)
                ReflectionTestUtils.getField(manager, "unknownProposerDeferCount");
    }

    private static Block block(long height) {
        Block b = new Block();
        b.nVersion = 1;
        b.nHeight = height;
        b.nTime = 1700000000L + height;
        b.hashPrevBlock = new byte[Block.HASH_SIZE];
        b.hashMerkleRoot = new byte[Block.HASH_SIZE];
        b.hashMerkleState = new byte[Block.HASH_SIZE];
        b.hashMerkleIncubate = new byte[Block.HASH_SIZE];
        b.nBits = new byte[Block.HASH_SIZE];
        b.nNonce = new byte[Block.HASH_SIZE];
        return b;
    }
}
