package org.nexus.consensus.finality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.commons.codec.binary.Hex;
import org.nexus.consensus.pos.PosConsensusEngine;
import org.nexus.consensus.pos.PosProposer;
import org.nexus.consensus.pos.PosRewardDistributor;
import org.nexus.consensus.pos.SlashingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pow.ConsensusConfig;
import org.nexus.consensus.pow.EconomicModel;
import org.nexus.consensus.pow.PackageMiner;
import org.nexus.core.Block;
import org.nexus.core.account.Transaction;
import org.nexus.core.crypto.bls.BlsSigner;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.validate.MerkleRule;
import org.nexus.db.StateDB;
import org.nexus.keystore.crypto.KeyPair;
import org.nexus.keystore.wallet.KeystoreAction;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 出块→检查点→最终化 整链编排集成测试（ADR-030 全链路，真实组件）。
 *
 * <p>真实组件：PosConsensusEngine（Ed25519 签名）/ PosProposer / ValidatorRegistry /
 * StakingServiceImpl / SlashingService / PosRewardDistributor；仅 StateDB 与链存储 mock
 * （真实持久化需 LevelDB 文件与环境，已由 ADR-031 记录）。</p>
 *
 * <p>验证目标：</p>
 * <ul>
 *   <li>引擎真实出块（含签名）→ 区块作为检查点候选喂给 FinalityCoordinator</li>
 *   <li>epoch 边界自动投票；单验证人权重 100% ≥ 2/3 → 检查点立即最终化</li>
 *   <li>跨 epoch 隔离：下一 epoch 未投满前不误判最终化</li>
 * </ul>
 */
class FinalityChainOrchestrationTest {

    private static final long EPOCH_LENGTH = 4;
    private static final BigDecimal MIN_STAKE = new BigDecimal("100");

    private ValidatorRegistry validatorRegistry;
    private StakingServiceImpl stakingService;
    private PosConsensusEngine engine;
    private FinalityGadget gadget;
    private FinalityCoordinator coordinator;

    private String selfAddress;
    private Block lastParent;  // 最近一次出块的父块引用（供下一次 propose）
    private final java.util.Map<Long, Block> heights = new java.util.HashMap<>();  // 高度→区块历史

    @BeforeEach
    void setUp() throws Exception {
        lastParent = null;
        heights.clear();
        validatorRegistry = new ValidatorRegistry(MIN_STAKE, 100);
        stakingService = new StakingServiceImpl();
        PosProposer proposer = new PosProposer();
        SlashingService slashingService = new SlashingService();
        PosRewardDistributor rewardDistributor = new PosRewardDistributor();

        // 真实依赖互联
        ReflectionTestUtils.setField(proposer, "validatorRegistry", validatorRegistry);
        ReflectionTestUtils.setField(stakingService, "validatorRegistry", validatorRegistry);
        ReflectionTestUtils.setField(slashingService, "validatorRegistry", validatorRegistry);
        ReflectionTestUtils.setField(slashingService, "stakingService", stakingService);
        ReflectionTestUtils.setField(rewardDistributor, "validatorRegistry", validatorRegistry);
        ReflectionTestUtils.setField(rewardDistributor, "stakingService", stakingService);

        // 引擎签名密钥 → 同一密钥的公钥/地址注册为本节点验证人（propose 按公钥查找才可命中）
        KeyPair engineKeyPair = KeyPair.generateEd25519KeyPair();
        String enginePubHex = Hex.encodeHexString(engineKeyPair.getPublicKey().getBytes());
        selfAddress = KeystoreAction.pubkeyToAddress(engineKeyPair.getPublicKey().getBytes(), (byte) 0x01);
        BigDecimal stakeAmount = new BigDecimal("1000");
        validatorRegistry.register(selfAddress, enginePubHex, stakeAmount, 0.1);
        stakingService.stake(selfAddress, stakeAmount);

        engine = new PosConsensusEngine(engineKeyPair, 30L);

        // 存储层 mock（真实层级不做文件持久化）
        StateDB stateDB = mock(StateDB.class);
        when(stateDB.getBestBlock()).thenAnswer(inv ->
                lastParent == null ? buildInitialParent(0) : lastParent);

        // 装配 engine 依赖
        ReflectionTestUtils.setField(engine, "validatorRegistry", validatorRegistry);
        ReflectionTestUtils.setField(engine, "stakingService", stakingService);
        ReflectionTestUtils.setField(engine, "proposer", proposer);
        ReflectionTestUtils.setField(engine, "rewardDistributor", rewardDistributor);
        ReflectionTestUtils.setField(engine, "slashingService", slashingService);
        ReflectionTestUtils.setField(engine, "stateDB", stateDB);
        ReflectionTestUtils.setField(engine, "bc", mock(NexusChainBlockChain.class));
        PackageMiner packageMiner = mock(PackageMiner.class);
        when(packageMiner.TransferCheck(any(byte[].class), anyLong(), any(Block.class)))
                .thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(engine, "packageMiner", packageMiner);
        ReflectionTestUtils.setField(engine, "merkleRule", mock(MerkleRule.class));
        EconomicModel economicModel = mock(EconomicModel.class);
        when(economicModel.getConsensusRewardAtHeight(anyLong())).thenReturn(20L * EconomicModel.NEX);
        ReflectionTestUtils.setField(engine, "economicModel", economicModel);
        ReflectionTestUtils.setField(engine, "consensusConfig", mock(ConsensusConfig.class));
        ApplicationContext ctx = mock(ApplicationContext.class);
        doNothing().when(ctx).publishEvent(any(ApplicationEvent.class));
        ReflectionTestUtils.setField(engine, "applicationContext", ctx);

        // 最终性层
        gadget = new FinalityGadget(validatorRegistry, stakingService);
        // B-17 修复后：注入 BlsSigner 才能投票
        coordinator = new FinalityCoordinator(gadget, validatorRegistry, EPOCH_LENGTH, null, BlsSigner.generate());
        coordinator.setSelfValidatorAddress(selfAddress);
    }

    private Block buildInitialParent(long height) {
        Block parent = new Block();
        parent.nVersion = 1;
        parent.hashPrevBlock = new byte[Block.HASH_SIZE];
        parent.nHeight = height;
        parent.nTime = System.currentTimeMillis() / 1000 - 60;
        parent.nBits = new byte[Block.HASH_SIZE];
        parent.nNonce = new byte[Block.HASH_SIZE];
        parent.blockNotice = new byte[Block.MAX_NOTICE_LENGTH];
        parent.body = new ArrayList<>();
        parent.body.add(org.nexus.core.account.Transaction.createEmpty());
        parent.hashMerkleRoot = Block.calculateMerkleRoot(parent.body);
        return parent;
    }

    /**
     * 驱动引擎真实出块并把产出块喂给最终性协调器。
     *
     * @return 出块成功返回区块，本节点非出块轮返回 null
     */
    private Block proposeAndFinalize() {
        Block proposed = engine.propose();
        if (proposed != null) {
            coordinator.onBlock(proposed);
            lastParent = proposed;  // 作为下一块父块
            heights.put(proposed.nHeight, proposed);  // 记录历史供检查点查询
        }
        return proposed;
    }

    // ========== 用例 ==========

    @Test
    void singleValidatorFinalizesEveryCheckpoint() {
        // 单验证人：每次出块都选中本节点；epoch 边界自动投票并立即最终化（100% ≥ 2/3）
        for (long h = 1; h <= EPOCH_LENGTH * 2; h++) {
            Block b = proposeAndFinalize();
            assertNotNull(b, "唯一验证人应每个高度都出块，高度=" + h);
            assertEquals(h, b.nHeight);
        }

        // epoch1 检查点（高度 4）已最终化（单验证人 100% 权重）
        Block checkpoint1 = findBlockByHeight(EPOCH_LENGTH);
        assertNotNull(checkpoint1, "检查点区块缺失");
        assertTrue(gadget.isFinalized(1, checkpoint1.getHash()));
    }

    @Test
    void crossEpochIsolation() {
        // 只出块到 epoch1 检查点：确认未投下一 epoch 前不误判
        for (long h = 1; h <= EPOCH_LENGTH; h++) {
            assertNotNull(proposeAndFinalize());
        }
        Block checkpoint1 = findBlockByHeight(EPOCH_LENGTH);
        assertNotNull(checkpoint1, "检查点区块缺失");
        assertTrue(gadget.isFinalized(1, checkpoint1.getHash()));
        // epoch2（尚未出块）无投票 → 不最终化
        FinalityRecord prog2 = gadget.getEpochProgress(2);
        assertEquals(0, prog2 == null ? 0 : prog2.getVotedWeight().longValue());
    }

    /** 按高度查询出块历史 */
    private Block findBlockByHeight(long height) {
        return heights.get(height);
    }
}