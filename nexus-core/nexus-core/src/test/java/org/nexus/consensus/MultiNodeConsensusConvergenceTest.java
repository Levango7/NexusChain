package org.nexus.consensus;

import org.junit.jupiter.api.*;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.FinalityRecord;
import org.nexus.consensus.finality.Vote;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 3节点共识收敛测试。
 *
 * <p>模拟 3 个独立共识节点实例，每个节点有自己的 {@link ValidatorRegistry} /
 * {@link StakingService} / {@link FinalityGadget}，通过内存 P2P 通道交换投票，
 * 验证所有节点对同一检查点达成 finalized（共识收敛）。
 *
 * <p>这是对 {@link FinalityEndToEndIntegrationTest}（单进程 3 验证人投票到同一 gadget）
 * 的升级：本测试中每个节点有独立的 gadget 实例，投票通过广播传递，
 * 验证多节点状态最终收敛一致。
 *
 * <p>纯 Java 沙箱，不启动 Spring 容器，不需要 docker。
 *
 * @since 2.11.0
 */
@DisplayName("3节点共识收敛：多节点独立实例投票+收敛")
class MultiNodeConsensusConvergenceTest {

    private static final String[] VALIDATORS = {"val1", "val2", "val3"};
    private static final BigDecimal STAKE = new BigDecimal("1000");

    private List<ConsensusNode> nodes;

    @BeforeEach
    void setUp() {
        nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            nodes.add(new ConsensusNode("node-" + i));
        }
    }

    /** 广播投票到所有节点 */
    private void broadcastVote(Vote vote) {
        for (ConsensusNode node : nodes) {
            node.submitVote(vote);
        }
    }

    /** 广播投票到指定节点列表 */
    private void broadcastVoteTo(Vote vote, List<ConsensusNode> targets) {
        for (ConsensusNode node : targets) {
            node.submitVote(vote);
        }
    }

    private byte[] checkpoint(long epoch) {
        return new byte[]{(byte) epoch, 1, 2, 3};
    }

    private Vote vote(long epoch, String validator) {
        return new Vote(epoch, checkpoint(epoch), validator, new byte[32]);
    }

    // ==================== 测试用例 ====================

    @Test
    @Order(1)
    @DisplayName("1. 3节点对同一检查点投票→所有节点finalized（共识收敛）")
    void threeNodesReachConsensus() {
        long epoch = 1L;

        // 每个验证人投票，广播给所有节点
        for (String validator : VALIDATORS) {
            broadcastVote(vote(epoch, validator));
        }

        // 所有节点应 finalized
        for (ConsensusNode node : nodes) {
            assertTrue(node.isFinalized(epoch, checkpoint(epoch)),
                    node.id + " 应 finalized");
        }
    }

    @Test
    @Order(2)
    @DisplayName("2. 1节点延迟→其他2节点先finalized→延迟节点追上后也finalized")
    void oneNodeLagging_catchesUp() {
        long epoch = 2L;
        ConsensusNode lagging = nodes.get(2);
        List<ConsensusNode> active = nodes.subList(0, 2);

        // val1, val2 投票，只广播给前2个节点
        broadcastVoteTo(vote(epoch, "val1"), active);
        broadcastVoteTo(vote(epoch, "val2"), active);

        // 前2个节点 finalized（2票达 quorum: 2000*3 >= 3000*2 → 6000 >= 6000）
        assertTrue(nodes.get(0).isFinalized(epoch, checkpoint(epoch)), "node-0 应 finalized");
        assertTrue(nodes.get(1).isFinalized(epoch, checkpoint(epoch)), "node-1 应 finalized");
        assertFalse(lagging.isFinalized(epoch, checkpoint(epoch)), "node-2 延迟不应 finalized");

        // 延迟节点追上：重放投票
        lagging.submitVote(vote(epoch, "val1"));
        lagging.submitVote(vote(epoch, "val2"));

        assertTrue(lagging.isFinalized(epoch, checkpoint(epoch)), "node-2 追上后应 finalized");
    }

    @Test
    @Order(3)
    @DisplayName("3. 并发投票→所有节点收敛到同一状态")
    void concurrentVotes_allNodesConverge() throws InterruptedException {
        long epoch = 3L;
        int n = 3;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch latch = new CountDownLatch(n);

        for (int i = 0; i < n; i++) {
            final String validator = VALIDATORS[i];
            pool.submit(() -> {
                try {
                    broadcastVote(vote(epoch, validator));
                } finally {
                    latch.countDown();
                }
            });
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS), "并发投票应在 5s 内完成");
        pool.shutdown();

        for (ConsensusNode node : nodes) {
            assertTrue(node.isFinalized(epoch, checkpoint(epoch)),
                    node.id + " 并发投票后应 finalized");
        }
    }

    @Test
    @Order(4)
    @DisplayName("4. 不同epoch独立finalized→互不干扰")
    void differentCheckpoints_independentFinalization() {
        for (long epoch = 10; epoch <= 12; epoch++) {
            for (String validator : VALIDATORS) {
                broadcastVote(vote(epoch, validator));
            }
        }

        for (long epoch = 10; epoch <= 12; epoch++) {
            for (ConsensusNode node : nodes) {
                assertTrue(node.isFinalized(epoch, checkpoint(epoch)),
                        node.id + " epoch=" + epoch + " 应 finalized");
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("5. 1节点宕机→其他2节点仍能共识（2/3 quorum）")
    void nodeDown_otherTwoReachConsensus() {
        long epoch = 5L;
        ConsensusNode downNode = nodes.get(2);

        // 模拟 node-2 宕机：val3 在所有节点标记为 INACTIVE
        for (ConsensusNode node : nodes) {
            node.setValidatorInactive("val3");
        }

        // 只有 val1, val2 投票（val3 宕机）
        broadcastVote(vote(epoch, "val1"));
        broadcastVote(vote(epoch, "val2"));

        // 所有节点应 finalized（2票达 quorum: total=2000, 2000*3 >= 2000*2）
        for (ConsensusNode node : nodes) {
            assertTrue(node.isFinalized(epoch, checkpoint(epoch)),
                    node.id + " 1节点宕机后2票应达 quorum");
        }

        // node-2 恢复：val3 重新 ACTIVE
        for (ConsensusNode node : nodes) {
            node.setValidatorActive("val3");
        }
        // 新 epoch 投票，3节点共识
        long epoch2 = 6L;
        for (String validator : VALIDATORS) {
            broadcastVote(vote(epoch2, validator));
        }
        for (ConsensusNode node : nodes) {
            assertTrue(node.isFinalized(epoch2, checkpoint(epoch2)),
                    node.id + " 恢复后应 finalized");
        }
    }

    // ==================== 共识节点模拟 ====================

    /**
     * 共识节点：独立的 ValidatorRegistry + StakingService + FinalityGadget。
     * 注册相同的 3 个验证人，维护自己的 finality 状态。
     */
    static class ConsensusNode {
        final String id;
        final ValidatorRegistry registry;
        final StakingService stakingService;
        final FinalityGadget gadget;

        ConsensusNode(String id) {
            this.id = id;
            this.registry = new ValidatorRegistry();
            this.stakingService = mock(StakingService.class);
            // 注册相同的 3 个验证人
            for (String v : VALIDATORS) {
                registry.register(v, "pub-" + v, STAKE, 0.1);
                when(stakingService.getStake(v)).thenReturn(STAKE);
            }
            this.gadget = new FinalityGadget(registry, stakingService);
        }

        void submitVote(Vote vote) {
            gadget.submitVote(vote);
        }

        boolean isFinalized(long epoch, byte[] checkpoint) {
            return gadget.isFinalized(epoch, checkpoint);
        }

        void setValidatorInactive(String address) {
            var v = registry.getValidator(address);
            if (v != null) v.setStatus(ValidatorStatus.INACTIVE);
        }

        void setValidatorActive(String address) {
            var v = registry.getValidator(address);
            if (v != null) v.setStatus(ValidatorStatus.ACTIVE);
        }
    }
}