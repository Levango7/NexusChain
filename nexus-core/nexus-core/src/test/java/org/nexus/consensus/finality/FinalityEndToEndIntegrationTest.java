package org.nexus.consensus.finality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.finality.net.FinalityVoteBroadcaster;
import org.nexus.consensus.pos.SlashingService;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.core.Block;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.core.event.NewBlockMinedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NexFinality 端到端集成测试（任务 1：出块 → epoch 边界 → 投票 → 最终化闭环）。
 *
 * <p>真实组件接线：ValidatorRegistry + StakingServiceImpl + FinalityGadget +
 * FinalityCoordinator + FinalityVoteBroadcaster + SlashingService，
 * 通过 Spring 事件总线驱动 NewBlockMinedEvent 触发自动投票。</p>
 *
 * <p>验证目标：</p>
 * <ul>
 *   <li>创世块（高度 1）产生后，epoch=1 的第一个检查点（高度=epochLength）自动投票</li>
 *   <li>3 个验证人各投 1 票后，质押权重达 2/3 → 检查点最终化（FINALIZED）</li>
 *   <li>双签作恶触发罚没，作恶人权重被没收、投票数被回退</li>
 *   <li>非检查点高度不产生投票</li>
 * </ul>
 */
class FinalityEndToEndIntegrationTest {

    private ValidatorRegistry validatorRegistry;
    private StakingService stakingService;
    private FinalityGadget gadget;
    private FinalityCoordinator coordinator;
    private FinalityVoteBroadcaster broadcaster;
    private TestPublisher publisher;

    /** P0-1 修复后：每个验证人的真实 Ed25519 密钥（注册公钥与投票签名密钥一致） */
    private final Map<String, org.nexus.crypto.ed25519.Ed25519KeyPair> validatorKeys = new java.util.HashMap<>();

    private static final long EPOCH_LENGTH = 4;  // 每 4 个块一个 epoch 检查点
    private static final byte[] EMPTY_SIG = new byte[0];

    private static final String[] VALIDATORS = {"v1", "v2", "v3"};

    static class TestPublisher implements ApplicationEventPublisher {
        final List<NewBlockMinedEvent> events = new ArrayList<>();
        @Override public void publishEvent(ApplicationEvent event) {
            if (event instanceof NewBlockMinedEvent) events.add((NewBlockMinedEvent) event);
        }
        @Override public void publishEvent(Object event) {
            if (event instanceof ApplicationEvent) publishEvent((ApplicationEvent) event);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        validatorRegistry = new ValidatorRegistry(new BigDecimal("100"), 100);
        stakingService = newStaking(validatorRegistry);

        // 注册 3 个验证人，各质押 300（总权重 900，2/3 阈值 = 600）
        // P0-1 绑定校验：注册真实 Ed25519 公钥
        validatorKeys.clear();
        for (String v : VALIDATORS) {
            org.nexus.crypto.ed25519.Ed25519KeyPair kp = org.nexus.crypto.ed25519.Ed25519.generateKeyPair();
            validatorKeys.put(v, kp);
            validatorRegistry.register(v, org.apache.commons.codec.binary.Hex.encodeHexString(
                    kp.getPublicKey().getEncoded()), new BigDecimal("300"), 0.1);
            Validator val = validatorRegistry.getValidator(v);
            val.setStatus(ValidatorStatus.ACTIVE);
            stakingService.stake(v, new BigDecimal("300"));
        }

        publisher = new TestPublisher();
        gadget = new FinalityGadget(validatorRegistry, stakingService);
        // P0-1 审计修复后：注入 Ed25519 密钥对才能投票
        coordinator = new FinalityCoordinator(gadget, validatorRegistry, EPOCH_LENGTH, null);
        var keyPair = validatorKeys.get("v1");
        coordinator.setVoteSigningKeyPair(keyPair.getPrivateKey(), keyPair.getPublicKey());
        broadcaster = new FinalityVoteBroadcaster(gadget, publisher);

        // 注入 slash 联动（M4：双签检测后自动罚没）
        SlashingService slashingService = new SlashingService();
        setField(slashingService, "validatorRegistry", validatorRegistry);
        setField(slashingService, "stakingService", stakingService);
        gadget.setSlashingService(slashingService);
    }

    private StakingService newStaking(ValidatorRegistry reg) {
        StakingServiceImpl s = new StakingServiceImpl();
        setField(s, "validatorRegistry", reg);
        return s;
    }

    private static void setField(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject " + field, e);
        }
    }

    /** 让指定验证人作为本节点（由 coordinator 驱动）产生投票；selfAddr=null 时空转 */
    private void makeCoordinator(String selfAddr) {
        coordinator = new FinalityCoordinator(gadget, validatorRegistry, EPOCH_LENGTH, null);
        if (selfAddr != null) {
            // P0-1 绑定校验：投票签名密钥必须是该验证人注册的密钥
            var keyPair = validatorKeys.get(selfAddr);
            coordinator.setVoteSigningKeyPair(keyPair.getPrivateKey(), keyPair.getPublicKey());
        }
        coordinator.setSelfValidatorAddress(selfAddr);
    }

    /** 驱动一块出块事件（进入 coordinator 判断逻辑） */
    private FinalityRecord driveBlock(long height, String hashSuffix) {
        Block block = new Block();
        block.nHeight = height;
        // Block.getHash() 依赖 hashCache（private），用反射设置
        try {
            Field f = Block.class.getDeclaredField("hashCache");
            f.setAccessible(true);
            f.set(block, ("cp-" + hashSuffix).getBytes());
        } catch (Exception e) {
            throw new RuntimeException("Cannot set block hashCache", e);
        }
        return coordinator.onBlock(block);
    }

    // ========== 用例 ==========

    @Test
    void fullEpochFinalizes() {
        // 3 个验证人每人作为 coordinator 一票，通过 gadget 聚合
        FinalityRecord last = null;
        for (String v : VALIDATORS) {
            makeCoordinator(v);
            last = driveBlock(EPOCH_LENGTH, "epoch1");
            assertNotNull(last, "coordinator 应产生投票");
        }

        // 投票达 2/3 → 检查点最终化
        assertTrue(last.isFinalized(), "3 票（权重 900）应使检查点最终化");
        assertTrue(gadget.isFinalized(1, "cp-epoch1".getBytes()));
        assertEquals(100, last.progressPercent(), "权重 3/3 = 100%");
    }

    @Test
    void notCheckpointSkipsVote() {
        makeCoordinator("v1");
        assertNull(driveBlock(3, "x"), "非检查点高度不应产生投票");
    }

    @Test
    void equivocationAcrossVotesTriggersSlash() {
        // v1 在 epoch1 对 CP1 投票
        makeCoordinator("v1");
        FinalityRecord r1 = driveBlock(EPOCH_LENGTH, "a");
        assertNotNull(r1);

        // v1 又对同一 epoch 的不同 checkpoint 投票（双签）
        driveBlock(EPOCH_LENGTH, "b");  // 同高度不同 hash → 同 epoch 不同检查点

        // 双签证据被捕 + slash 被触发（质押归零 + 状态置 SLASHED）
        assertEquals(1, gadget.getDetectedEquivocations().size());
        assertEquals(0, stakingService.getStake("v1").compareTo(BigDecimal.ZERO));
        assertEquals(ValidatorStatus.SLASHED, validatorRegistry.getValidator("v1").getStatus());
    }

    @Test
    void noVoteWhenSelfNotBound() {
        makeCoordinator(null);   // 未绑定验证人地址
        assertNull(driveBlock(EPOCH_LENGTH, "x"), "协调器不绑验证人时空转");
    }

    @Test
    void slashedValidatorStopsVoting() {
        // v1 先投一票
        makeCoordinator("v1");
        assertNotNull(driveBlock(EPOCH_LENGTH, "epoch1"));

        // 罚没 v1
        stakeValidatorStatus("v1", ValidatorStatus.SLASHED);

        // v1 再投下一个 epoch 检查点（高度 8）
        assertNull(driveBlock(EPOCH_LENGTH * 2, "epoch2"), "SLASHED 后应停止投票");
        assertFalse(gadget.isFinalized(2, "cp-epoch2".getBytes()), "epoch2 不应被 v1 推进最终化");
    }

    /** 直接改验证人状态（不做 slash 逻辑） */
    private void stakeValidatorStatus(String addr, ValidatorStatus status) {
        Validator v = validatorRegistry.getValidator(addr);
        if (v != null) v.setStatus(status);
    }
}
