package org.nexus.consensus;

import org.junit.jupiter.api.*;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.FinalityRecord;
import org.nexus.consensus.finality.Vote;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 共识链路混沌测试：验证人节点宕机 + Finality quorum 容错。
 *
 * <p>验证共识层在验证人节点故障（INACTIVE）期间的行为：
 * 活跃验证人集合正确缩减、Finality 投票 quorum 容错、故障恢复后重新参与共识。
 * 纯 Java 沙箱，不启动 Spring 容器。
 *
 * <p>混沌场景：
 * <ul>
 *   <li>验证人宕机 → 活跃数减少 → 恢复 → 重新加入</li>
 *   <li>多验证人部分宕机 → 活跃数量正确</li>
 *   <li>1/3 验证人宕机 → 剩余 2/3 达 quorum → finality 确认</li>
 *   <li>验证人宕机后恢复 → 能继续投票</li>
 *   <li>全部宕机 → 无法 finalized → 恢复 → 能 finalized</li>
 * </ul>
 *
 * @since 2.9.0
 */
@DisplayName("共识链路混沌测试：验证人宕机+Finality quorum")
class ConsensusChaosTest {

    private ValidatorRegistry registry;
    private StakingService stakingService;

    @BeforeEach
    void setUp() {
        registry = new ValidatorRegistry();
        stakingService = mock(StakingService.class);
    }

    /** 注册一个验证人，质押 stake，返回其地址 */
    private String registerValidator(String address, BigDecimal stake) {
        registry.register(address, "pub_" + address, stake, 0.1);
        when(stakingService.getStake(address)).thenReturn(stake);
        return address;
    }

    /** 模拟验证人宕机（INACTIVE） */
    private void takeDown(String address) {
        Validator v = registry.getValidator(address);
        assertNotNull(v, "验证人应存在: " + address);
        v.setStatus(ValidatorStatus.INACTIVE);
    }

    /** 模拟验证人恢复（ACTIVE） */
    private void bringUp(String address) {
        Validator v = registry.getValidator(address);
        assertNotNull(v, "验证人应存在: " + address);
        v.setStatus(ValidatorStatus.ACTIVE);
    }

    private byte[] checkpoint(long epoch) {
        return new byte[]{(byte) epoch, 1, 2, 3};
    }

    private Vote vote(long epoch, String validator) {
        // 签名需 >= 32 字节通过 CollectingAggregator 格式校验
        return new Vote(epoch, checkpoint(epoch), validator, new byte[32]);
    }

    // ==================== 混沌测试用例 ====================

    @Test
    @Order(1)
    @DisplayName("1. 验证人宕机→活跃数减少→恢复→重新加入")
    void validatorDown_activeCountDecreases_thenRecover() {
        registerValidator("addr1", new BigDecimal("1000"));
        registerValidator("addr2", new BigDecimal("1000"));
        registerValidator("addr3", new BigDecimal("1000"));

        assertEquals(3, registry.getActiveValidators().size(), "初始3个活跃验证人");

        // addr1 宕机
        takeDown("addr1");
        assertEquals(2, registry.getActiveValidators().size(), "宕机后应剩2个活跃");

        // addr2 也宕机
        takeDown("addr2");
        assertEquals(1, registry.getActiveValidators().size(), "再宕1个应剩1个活跃");

        // addr1 恢复
        bringUp("addr1");
        assertEquals(2, registry.getActiveValidators().size(), "恢复1个应有2个活跃");

        // addr2 恢复
        bringUp("addr2");
        assertEquals(3, registry.getActiveValidators().size(), "全部恢复应有3个活跃");
    }

    @Test
    @Order(2)
    @DisplayName("2. 多验证人部分宕机→活跃数量正确")
    void multipleValidatorsPartialDown() {
        for (int i = 1; i <= 5; i++) {
            registerValidator("v" + i, new BigDecimal("1000"));
        }
        assertEquals(5, registry.getActiveValidators().size());

        // 宕机 v2, v4
        takeDown("v2");
        takeDown("v4");
        assertEquals(3, registry.getActiveValidators().size(), "宕2个应剩3个活跃");

        // 验证活跃集合内容
        var active = registry.getActiveValidators();
        assertTrue(active.stream().anyMatch(v -> v.getAddress().equals("v1")));
        assertTrue(active.stream().noneMatch(v -> v.getAddress().equals("v2")));
        assertTrue(active.stream().anyMatch(v -> v.getAddress().equals("v3")));
        assertTrue(active.stream().noneMatch(v -> v.getAddress().equals("v4")));
        assertTrue(active.stream().anyMatch(v -> v.getAddress().equals("v5")));

        // 恢复 v4
        bringUp("v4");
        assertEquals(4, registry.getActiveValidators().size());
    }

    @Test
    @Order(3)
    @DisplayName("3. 1/3验证人宕机→剩余2/3达quorum→finality确认")
    void finalityWithOneThirdDown_reachesQuorum() {
        // 4 个验证人各质押 1000，total = 4000，quorum 需 >= 2666.67（即至少 3 票）
        for (int i = 1; i <= 4; i++) {
            registerValidator("val" + i, new BigDecimal("1000"));
        }

        FinalityGadget gadget = new FinalityGadget(registry, stakingService);
        long epoch = 10L;

        // val4 宕机（1/4 宕机，剩余 3/4 > 2/3）
        takeDown("val4");

        // 剩余 3 个验证人投票（total=3000, voted=3000, 3000*3=9000 >= 3000*2=6000 → finalized）
        FinalityRecord r1 = gadget.submitVote(vote(epoch, "val1"));
        assertFalse(r1.isFinalized(), "1票不应 finalized（1000*3=3000 < 3000*2=6000）");

        // 2票: voted=2000, total=3000, 2000*3=6000 >= 3000*2=6000 → finalized
        FinalityRecord r2 = gadget.submitVote(vote(epoch, "val2"));
        assertTrue(r2.isFinalized(), "2票达 quorum（2000*3 >= 3000*2）应 finalized");
        assertTrue(gadget.isFinalized(epoch, checkpoint(epoch)), "检查点应已 finalized");
    }

    @Test
    @Order(4)
    @DisplayName("4. 验证人宕机后恢复→能继续投票")
    void validatorDown_thenRecover_canVoteAgain() {
        registerValidator("node1", new BigDecimal("1000"));
        registerValidator("node2", new BigDecimal("1000"));
        registerValidator("node3", new BigDecimal("1000"));

        FinalityGadget gadget = new FinalityGadget(registry, stakingService);
        long epoch = 20L;

        // node1 宕机
        takeDown("node1");

        // node1 投票 → 不累积权重（INACTIVE）
        FinalityRecord r1 = gadget.submitVote(vote(epoch, "node1"));
        assertFalse(r1.isFinalized(), "INACTIVE 验证人投票不应累积权重");

        // node1 恢复
        bringUp("node1");

        // node1 恢复后投票 → 累积权重
        // 但 node1 之前已经投过票（voters set 包含 node1），幂等拒绝
        // 所以需要用不同的 epoch
        long epoch2 = 21L;
        FinalityRecord r2 = gadget.submitVote(vote(epoch2, "node1"));
        assertFalse(r2.isFinalized(), "1票不应 finalized");

        // node2, node3 也投票
        gadget.submitVote(vote(epoch2, "node2"));
        FinalityRecord r3 = gadget.submitVote(vote(epoch2, "node3"));
        assertTrue(r3.isFinalized(), "3票应 finalized（3000*3 >= 3000*2）");
        assertTrue(gadget.isFinalized(epoch2, checkpoint(epoch2)));
    }

    @Test
    @Order(5)
    @DisplayName("5. 全部宕机→无法finalized→恢复→能finalized")
    void allValidatorsDown_noFinality_thenRecover() {
        registerValidator("a1", new BigDecimal("1000"));
        registerValidator("a2", new BigDecimal("1000"));

        FinalityGadget gadget = new FinalityGadget(registry, stakingService);
        long epoch = 30L;

        // 全部宕机
        takeDown("a1");
        takeDown("a2");

        // 投票 → 不累积权重 → 不 finalized
        FinalityRecord r1 = gadget.submitVote(vote(epoch, "a1"));
        assertFalse(r1.isFinalized(), "全部宕机时不应 finalized");
        FinalityRecord r2 = gadget.submitVote(vote(epoch, "a2"));
        assertFalse(r2.isFinalized(), "全部宕机时不应 finalized");
        assertFalse(gadget.isFinalized(epoch, checkpoint(epoch)));

        // 恢复 a1
        bringUp("a1");
        // a1 用新 epoch 投票（旧 epoch 已有 a1 的投票记录，幂等拒绝）
        long epoch2 = 31L;
        FinalityRecord r3 = gadget.submitVote(vote(epoch2, "a1"));
        // total=1000（只有 a1 ACTIVE），voted=1000，1000*3 >= 1000*2 → finalized
        assertTrue(r3.isFinalized(), "恢复后单验证人达 quorum 应 finalized（1000*3 >= 1000*2）");
        assertTrue(gadget.isFinalized(epoch2, checkpoint(epoch2)));
    }
}