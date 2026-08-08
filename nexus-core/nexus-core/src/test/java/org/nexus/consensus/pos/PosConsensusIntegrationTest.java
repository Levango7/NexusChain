package org.nexus.consensus.pos;

import org.apache.commons.codec.binary.Hex;
import org.junit.Before;
import org.junit.Test;
import org.nexus.consensus.pow.ConsensusConfig;
import org.nexus.consensus.pow.EconomicModel;
import org.nexus.consensus.pow.PackageMiner;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.account.Transaction;
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PoS 共识集成测试。
 *
 * <p>验证 PoS 模式下 propose 产出签名区块、validate 校验链完整、
 * 共识切换不破坏现有链。使用真实的 ValidatorRegistry / StakingServiceImpl /
 * PosProposer / SlashingService / PosRewardDistributor，Mock StateDB /
 * PackageMiner / NexusChainBlockChain / ApplicationContext。</p>
 *
 * <p><b>v1.9.3 fail-closed 改造</b>：测试不再依赖「公钥留空、验签降级为
 * nNonce 非零」的旧行为。节点签名密钥显式生成并绑定到注册验证人
 * （公钥 + 由公钥推导的地址），propose/validate 走真实 Ed25519
 * 签名/验签路径；新增伪造签名、缺失公钥、密钥未绑定、非本节点轮次
 * 四类拒绝用例。</p>
 *
 * <h3>测试场景</h3>
 * <ol>
 *   <li>propose 产出有效区块：非null、高度正确、含coinbase、nNonce非零</li>
 *   <li>validate 校验链完整：合法区块通过；提案者不在集合/质押不足/已被罚没 → 拒绝</li>
 *   <li>共识切换不破坏现有链：ConsensusConfig mode=dpos/pos 切换不影响现有区块</li>
 *   <li>连续出块：多次 propose 高度递增、prevHash 匹配</li>
 *   <li>fail-closed：伪造签名/全零签名/公钥缺失的区块被拒绝</li>
 *   <li>fail-closed：签名密钥未绑定验证人时拒绝出块；非本节点轮次跳过出块</li>
 * </ol>
 *
 * @since 1.2
 */
public class PosConsensusIntegrationTest {

    // 合法 Base58 地址（来自 genesis 配置，可通过 KeystoreAction.addressToPubkeyHash 转换）
    private static final String VALIDATOR_B_ADDR = "1AMmLXt2Pgwt9nomua8aEYzqre3nxxu2KM";
    private static final String UNKNOWN_ADDR = "1BCaLumRPQwBQEE7oCqdjgtvU4b6sbquYu";
    private static final String POOR_ADDR = "1BGqUxFsBh8bBwgVu5AkHeg1SoBB7nVQfx";
    private static final String NO_KEY_ADDR = "1PpBHEx782C4VrtnQcJRTogn5UYmzCWAPH";

    private static final BigDecimal MIN_STAKE = new BigDecimal("1000");
    private static final BigDecimal STAKE_AMOUNT = new BigDecimal("5000");

    // 真实组件
    private ValidatorRegistry validatorRegistry;
    private StakingServiceImpl stakingService;
    private PosProposer proposer;
    private SlashingService slashingService;
    private PosRewardDistributor rewardDistributor;

    // Mock 组件
    private StateDB stateDB;
    private PackageMiner packageMiner;
    private NexusChainBlockChain blockChain;
    private MerkleRule merkleRule;
    private EconomicModel economicModel;
    private ConsensusConfig consensusConfig;
    private ApplicationContext applicationContext;

    // 节点签名密钥（显式生成，注册为验证人公钥，propose/validate 走真实验签）
    private KeyPair engineKeyPair;
    private String enginePubKeyHex;
    private String selfAddress;

    // 被测对象
    private PosConsensusEngine engine;

    @Before
    public void setUp() throws Exception {
        // 真实组件
        validatorRegistry = new ValidatorRegistry(MIN_STAKE, 100);
        stakingService = new StakingServiceImpl();
        proposer = new PosProposer();
        slashingService = new SlashingService();
        rewardDistributor = new PosRewardDistributor();

        // Mock 组件
        stateDB = mock(StateDB.class);
        packageMiner = mock(PackageMiner.class);
        blockChain = mock(NexusChainBlockChain.class);
        merkleRule = mock(MerkleRule.class);
        economicModel = mock(EconomicModel.class);
        consensusConfig = mock(ConsensusConfig.class);
        applicationContext = mock(ApplicationContext.class);

        // 注入真实组件间的依赖
        ReflectionTestUtils.setField(proposer, "validatorRegistry", validatorRegistry);
        ReflectionTestUtils.setField(stakingService, "validatorRegistry", validatorRegistry);
        ReflectionTestUtils.setField(slashingService, "validatorRegistry", validatorRegistry);
        ReflectionTestUtils.setField(slashingService, "stakingService", stakingService);
        ReflectionTestUtils.setField(rewardDistributor, "validatorRegistry", validatorRegistry);
        ReflectionTestUtils.setField(rewardDistributor, "stakingService", stakingService);

        // 生成节点签名密钥，并由公钥推导本节点地址（与注册验证人绑定）
        engineKeyPair = KeyPair.generateEd25519KeyPair();
        enginePubKeyHex = Hex.encodeHexString(engineKeyPair.getPublicKey().getBytes());
        selfAddress = KeystoreAction.pubkeyToAddress(engineKeyPair.getPublicKey().getBytes(), (byte) 0x01);

        // 构造 engine（显式注入签名密钥）
        engine = new PosConsensusEngine(engineKeyPair, 30L);

        // 注入 engine 依赖
        ReflectionTestUtils.setField(engine, "validatorRegistry", validatorRegistry);
        ReflectionTestUtils.setField(engine, "stakingService", stakingService);
        ReflectionTestUtils.setField(engine, "proposer", proposer);
        ReflectionTestUtils.setField(engine, "rewardDistributor", rewardDistributor);
        ReflectionTestUtils.setField(engine, "slashingService", slashingService);
        ReflectionTestUtils.setField(engine, "stateDB", stateDB);
        ReflectionTestUtils.setField(engine, "bc", blockChain);
        ReflectionTestUtils.setField(engine, "packageMiner", packageMiner);
        ReflectionTestUtils.setField(engine, "merkleRule", merkleRule);
        ReflectionTestUtils.setField(engine, "economicModel", economicModel);
        ReflectionTestUtils.setField(engine, "consensusConfig", consensusConfig);
        ReflectionTestUtils.setField(engine, "applicationContext", applicationContext);

        // 默认 stub
        when(economicModel.getConsensusRewardAtHeight(anyLong())).thenReturn(20L * EconomicModel.NEX);
        when(packageMiner.TransferCheck(any(byte[].class), anyLong(), any(Block.class)))
                .thenReturn(Collections.emptyList());
        doNothing().when(applicationContext).publishEvent(any(ApplicationEvent.class));

        // 注册本节点为验证人：地址由引擎公钥推导，公钥与签名密钥对应，
        // propose/validate 全程走真实 Ed25519 签名/验签（fail-closed 回归）
        registerAndStake(selfAddress, enginePubKeyHex, STAKE_AMOUNT);
    }

    private void registerAndStake(String address, String publicKeyHex, BigDecimal stake) {
        validatorRegistry.register(address, publicKeyHex, stake, 0.1);
        stakingService.stake(address, stake);
    }

    /**
     * 构造一个合法父区块。
     */
    private Block buildParentBlock(long height) {
        Block parent = new Block();
        parent.nVersion = 1;
        parent.hashPrevBlock = new byte[Block.HASH_SIZE];
        parent.nHeight = height;
        parent.nTime = System.currentTimeMillis() / 1000 - 60;
        parent.nBits = new byte[Block.HASH_SIZE];
        parent.nNonce = new byte[Block.HASH_SIZE];
        parent.blockNotice = new byte[Block.MAX_NOTICE_LENGTH];
        parent.body = new ArrayList<>();
        parent.body.add(Transaction.createEmpty());
        parent.hashMerkleRoot = Block.calculateMerkleRoot(parent.body);
        parent.hashMerkleState = new byte[Block.HASH_SIZE];
        parent.hashMerkleIncubate = new byte[Block.HASH_SIZE];
        return parent;
    }

    /**
     * 构造一个区块，coinbase.to 指向指定提案者地址的 pubkeyHash，nNonce 非零（模拟签名）。
     */
    private Block buildBlockWithProposer(String proposerAddr, long height) {
        Block block = new Block();
        block.nVersion = 1;
        block.hashPrevBlock = new byte[Block.HASH_SIZE];
        block.nHeight = height;
        block.nTime = System.currentTimeMillis() / 1000;
        block.nBits = new byte[Block.HASH_SIZE];
        block.nNonce = new byte[Block.HASH_SIZE];
        block.nNonce[0] = 1; // nNonce 非零（模拟签名）
        block.blockNotice = new byte[Block.MAX_NOTICE_LENGTH];
        block.body = new ArrayList<>();
        Transaction coinbase = Transaction.createEmpty();
        try {
            coinbase.to = KeystoreAction.addressToPubkeyHash(proposerAddr);
        } catch (Exception e) {
            coinbase.to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
        }
        block.body.add(coinbase);
        block.hashMerkleRoot = Block.calculateMerkleRoot(block.body);
        block.hashMerkleState = new byte[Block.HASH_SIZE];
        block.hashMerkleIncubate = new byte[Block.HASH_SIZE];
        return block;
    }

    private static boolean isAllZeroes(byte[] arr) {
        for (byte b : arr) {
            if (b != 0) return false;
        }
        return true;
    }

    // ==================== 测试场景 1: propose 产出有效区块 ====================

    /**
     * 验证 propose() 产出非 null 区块、高度正确、含 coinbase、nNonce 非零（含签名）。
     */
    @Test
    public void testProposeProducesValidBlock() {
        Block parent = buildParentBlock(100);
        when(stateDB.getBestBlock()).thenReturn(parent);

        Block block = engine.propose();

        assertNotNull("propose 应返回非 null 区块", block);
        assertEquals("区块高度应为父区块 + 1", 101, block.nHeight);
        assertNotNull("区块 body 不应为空", block.body);
        assertFalse("区块 body 应含 coinbase 交易", block.body.isEmpty());
        Transaction coinbase = block.body.get(0);
        assertNotNull("coinbase 收款人不应为空", coinbase.to);
        assertFalse("coinbase 收款人应非全零（指向提案者 pubkeyHash）", isAllZeroes(coinbase.to));
        assertNotNull("nNonce 不应为空", block.nNonce);
        assertFalse("nNonce 应非零（含 Ed25519 签名 r）", isAllZeroes(block.nNonce));
    }

    // ==================== 测试场景 2: validate 校验链完整 ====================

    /**
     * 验证 propose() 产出的区块能通过 validate()（真实 Ed25519 验签）。
     */
    @Test
    public void testValidateAcceptsProposedBlock() {
        Block parent = buildParentBlock(100);
        when(stateDB.getBestBlock()).thenReturn(parent);

        Block block = engine.propose();
        assertNotNull("propose 应成功", block);

        assertTrue("propose 产出的区块应通过 validate（真实验签）", engine.validate(block));
    }

    /**
     * 验证提案者不在验证人集合时 validate 返回 false。
     */
    @Test
    public void testValidateRejectsUnknownProposer() {
        Block block = buildBlockWithProposer(UNKNOWN_ADDR, 101);

        assertFalse("提案者不在验证人集合应拒绝", engine.validate(block));
    }

    /**
     * 验证提案者质押不足时 validate 返回 false。
     */
    @Test
    public void testValidateRejectsInsufficientStake() {
        // 注册验证人但不 stake，getStake() 返回 0 < minStake
        validatorRegistry.register(POOR_ADDR, "", MIN_STAKE, 0.1);

        Block block = buildBlockWithProposer(POOR_ADDR, 101);

        assertFalse("质押不足应拒绝", engine.validate(block));
    }

    /**
     * 验证提案者已被罚没（SLASHED）时 validate 返回 false。
     */
    @Test
    public void testValidateRejectsSlashedProposer() {
        // 注册并质押一个无公钥验证人，再对其执行罚没（MALICIOUS → 状态置为 SLASHED）
        registerAndStake(NO_KEY_ADDR, "", STAKE_AMOUNT);
        slashingService.slash(NO_KEY_ADDR, SlashingService.Offense.MALICIOUS);

        Block block = buildBlockWithProposer(NO_KEY_ADDR, 101);

        assertFalse("已被罚没的提案者应拒绝", engine.validate(block));
    }

    // ==================== 测试场景 3: 共识切换不破坏现有链 ====================

    /**
     * 验证 ConsensusConfig mode 在 dpos/pos 间切换不影响现有区块的 validate 结果。
     */
    @Test
    public void testConsensusModeSwitchDoesNotBreakChain() {
        Block parent = buildParentBlock(100);
        when(stateDB.getBestBlock()).thenReturn(parent);

        Block block = engine.propose();
        assertNotNull("propose 应成功", block);

        // PoS 模式下 validate 通过
        when(consensusConfig.isPosMode()).thenReturn(true);
        when(consensusConfig.getConsensusMode()).thenReturn("pos");
        assertTrue("PoS 模式下应通过 validate", engine.validate(block));

        // 切换到 DPoS 模式，现有区块仍应能 validate（engine 的 validate 不依赖 mode）
        when(consensusConfig.isPosMode()).thenReturn(false);
        when(consensusConfig.getConsensusMode()).thenReturn("dpos");
        assertTrue("切换到 DPoS 模式后现有区块仍应通过 validate", engine.validate(block));

        // 切换回 PoS 模式
        when(consensusConfig.isPosMode()).thenReturn(true);
        when(consensusConfig.getConsensusMode()).thenReturn("pos");
        assertTrue("切回 PoS 模式后仍应通过 validate", engine.validate(block));
    }

    // ==================== 测试场景 4: 连续出块 ====================

    /**
     * 验证多次 propose() 产出区块高度递增、hashPrevBlock 匹配前一块 hash。
     */
    @Test
    public void testContinuousProposeHeightIncrement() {
        Block parent = buildParentBlock(100);
        when(stateDB.getBestBlock()).thenReturn(parent);

        byte[] prevHash = parent.getHash();

        for (int i = 1; i <= 5; i++) {
            Block block = engine.propose();
            assertNotNull("第 " + i + " 次出块不应为 null", block);
            assertEquals("第 " + i + " 块高度应递增", 100 + i, block.nHeight);
            assertArrayEquals("第 " + i + " 块的 hashPrevBlock 应匹配前一块 hash",
                    prevHash, block.hashPrevBlock);
            assertFalse("第 " + i + " 块 nNonce 应非零", isAllZeroes(block.nNonce));

            prevHash = block.getHash();
            // 更新 stateDB mock，使下一次 propose 以本块为父区块
            when(stateDB.getBestBlock()).thenReturn(block);
        }
    }

    // ==================== 测试场景 5: fail-closed 验签（v1.9.3 安全修复回归） ====================

    /**
     * 验证篡改签名（nNonce 翻转一位）的区块被 validate 拒绝。
     * 修复前该区块会走「验签失败降级为 nNonce 非零」路径被放行。
     */
    @Test
    public void testValidateRejectsForgedSignature() {
        Block parent = buildParentBlock(100);
        when(stateDB.getBestBlock()).thenReturn(parent);

        Block block = engine.propose();
        assertNotNull("propose 应成功", block);

        // 篡改签名 r 分量
        block.nNonce[0] ^= 0x01;

        assertFalse("伪造/被篡改签名的区块应被拒绝（fail-closed）", engine.validate(block));
    }

    /**
     * 验证 nNonce 全零（未签名）的区块被 validate 拒绝。
     */
    @Test
    public void testValidateRejectsUnsignedBlock() {
        Block parent = buildParentBlock(100);
        when(stateDB.getBestBlock()).thenReturn(parent);

        Block block = engine.propose();
        assertNotNull("propose 应成功", block);

        // 抹掉签名
        block.nNonce = new byte[Block.HASH_SIZE];
        block.blockNotice = new byte[Block.MAX_NOTICE_LENGTH];

        assertFalse("未签名区块应被拒绝", engine.validate(block));
    }

    /**
     * 验证提案者公钥缺失时 validate 拒绝（fail-closed）。
     * 修复前该场景「降级为 nNonce 非零校验」返回 true。
     */
    @Test
    public void testValidateRejectsWhenProposerPublicKeyMissing() {
        // 注册一个公钥为空的验证人并质押到门槛以上
        registerAndStake(NO_KEY_ADDR, "", STAKE_AMOUNT);

        Block block = buildBlockWithProposer(NO_KEY_ADDR, 101);

        assertFalse("提案者公钥缺失应拒绝（fail-closed，此前降级放行）", engine.validate(block));
    }

    // ==================== 测试场景 6: fail-closed 出块（密钥绑定 + 轮次判定） ====================

    /**
     * 验证签名密钥未绑定任何注册验证人时 propose 拒绝出块（返回 null）。
     */
    @Test
    public void testProposeRejectedWhenSigningKeyNotBound() {
        // 随机密钥的引擎：公钥与任何已注册验证人都不对应
        PosConsensusEngine stranger = new PosConsensusEngine();
        ReflectionTestUtils.setField(stranger, "validatorRegistry", validatorRegistry);

        assertNull("签名密钥未绑定验证人时应拒绝出块（fail-closed）", stranger.propose());
    }

    /**
     * 验证本轮被选中的提案者不是本节点时 propose 跳过出块（返回 null），
     * 不用自己的密钥替其他验证人签名。
     */
    @Test
    public void testProposeSkipsWhenNotOwnSlot() {
        PosProposer mockedProposer = mock(PosProposer.class);
        Validator other = new Validator(VALIDATOR_B_ADDR, "ff", STAKE_AMOUNT, 0.1, ValidatorStatus.ACTIVE);
        when(mockedProposer.selectProposer(anyLong())).thenReturn(other);
        ReflectionTestUtils.setField(engine, "proposer", mockedProposer);

        when(stateDB.getBestBlock()).thenReturn(buildParentBlock(100));

        assertNull("非本节点轮次应跳过出块", engine.propose());
    }
}
