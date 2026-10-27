package org.nexus.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.core.BlocksCache;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.PendingBlocksManager;
import org.nexus.core.validate.CompositeBlockRule;
import org.nexus.core.validate.MerkleRule;
import org.nexus.core.validate.Result;
import org.nexus.db.StateDB;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 弃权风险指标测试（PLAN-052）。
 *
 * <p>锁定：① 无 defer ⇒ 三个值为 0（不是 NaN——"没有拒绝"是明确事实）；
 * ② 低于阈值 ⇒ {@code abstain_risk=0} 但 max_count 反映真实计数；
 * ③ **达到阈值 5 ⇒ {@code abstain_risk=1}**（与 PLAN-051 同源）；
 * ④ 多高度独立：max_height/max_count 指向真实最高项而非求和。</p>
 */
class AbstainRiskMetricsTest {

    private PendingBlocksManager manager;
    private AbstainRiskMetrics metrics;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        manager = new PendingBlocksManager();
        StateDB stateDB = mock(StateDB.class);
        NexusChainBlockChain bc = mock(NexusChainBlockChain.class);
        CompositeBlockRule rule = mock(CompositeBlockRule.class);
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

        metrics = new AbstainRiskMetrics(manager);
        registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private double counter(String name) {
        return registry.get(name).functionCounter().count();
    }

    @Test
    void noDeferIsZeroNotNaN() {
        assertEquals(0.0, gauge("nexus_finality_abstain_risk"));
        assertEquals(0.0, gauge("nexus_finality_defer_max_height"));
        assertEquals(0.0, gauge("nexus_finality_defer_max_count"));
        assertEquals(0.0, counter("nexus_finality_defer_total"));
    }

    @Test
    void belowThresholdKeepsRiskAtZero() {
        defer(125, 3);
        assertEquals(0.0, gauge("nexus_finality_abstain_risk"), "低于阈值不得亮");
        assertEquals(3.0, gauge("nexus_finality_defer_max_count"));
        assertEquals(125.0, gauge("nexus_finality_defer_max_height"));
        assertEquals(3.0, counter("nexus_finality_defer_total"));
    }

    @Test
    void thresholdCrossingRaisesRisk() {
        defer(125, 5);
        assertEquals(1.0, gauge("nexus_finality_abstain_risk"),
                "达到阈值必须亮（与 PLAN-051 的 ERROR 升级同源）");
        assertEquals(5.0, gauge("nexus_finality_defer_max_count"));
        assertEquals(5.0, counter("nexus_finality_defer_total"));
    }

    @Test
    void heightsAreIndependentNotSummed() {
        defer(125, 4);
        defer(124, 2);
        // max 应指向 125（4 次），而不是 124，也不是 6（求和）
        assertEquals(125.0, gauge("nexus_finality_defer_max_height"));
        assertEquals(4.0, gauge("nexus_finality_defer_max_count"));
        assertEquals(6.0, counter("nexus_finality_defer_total"), "total 是求和语义");
        assertEquals(0.0, gauge("nexus_finality_abstain_risk"));
    }

    private void defer(long height, int times) {
        for (int i = 0; i < times; i++) {
            manager.addPendingBlocks(new BlocksCache(block(height)));
        }
        assertTrue(counter("nexus_finality_defer_total") >= times, "defer 应已计入");
    }

    private static Block block(long height) {
        Block b = new Block();
        b.nVersion = 1;
        b.nHeight = height;
        b.nTime = 1700000000L + height;
        b.hashPrevBlock = new byte[org.nexus.core.Block.HASH_SIZE];
        b.hashMerkleRoot = new byte[org.nexus.core.Block.HASH_SIZE];
        b.hashMerkleState = new byte[org.nexus.core.Block.HASH_SIZE];
        b.hashMerkleIncubate = new byte[org.nexus.core.Block.HASH_SIZE];
        b.nBits = new byte[org.nexus.core.Block.HASH_SIZE];
        b.nNonce = new byte[org.nexus.core.Block.HASH_SIZE];
        return b;
    }
}
