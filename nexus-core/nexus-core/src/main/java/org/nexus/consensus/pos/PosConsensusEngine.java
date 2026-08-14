package org.nexus.consensus.pos;

import org.apache.commons.codec.binary.Hex;
import org.nexus.consensus.pow.ConsensusConfig;
import org.nexus.consensus.pow.EconomicModel;
import org.nexus.consensus.pow.PackageMiner;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.account.Transaction;
import org.nexus.core.event.NewBlockMinedEvent;
import org.nexus.core.validate.MerkleRule;
import org.nexus.crypto.HashUtil;
import org.nexus.db.StateDB;
import org.nexus.encoding.BigEndian;
import org.nexus.keystore.crypto.KeyPair;
import org.nexus.keystore.crypto.ed25519.Ed25519DsaSigner;
import org.nexus.keystore.crypto.ed25519.Signature;
import org.nexus.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PoS 共识引擎。
 *
 * <p>集成验证人注册、质押、提案者选取、奖励分配与惩罚机制，
 * 是 PoS 共识的编排门面。实现 {@link PosConsensus} 接口。</p>
 *
 * <p>启用条件：{@code nexus.consensus.mode=pos}。默认（dpos）不会注入本引擎，
 * 避免空实现被误注入（曾经的 {@code @Primary} 静默地雷）。</p>
 *
 * <h3>出块流程</h3>
 * <ol>
 *   <li>从 {@link StateDB} 获取最新区块，计算新区块高度</li>
 *   <li>用 {@link PosProposer} 选取当前提案者（stake-weighted）</li>
 *   <li>构造 coinbase 交易（收款人为提案者 pubkeyHash）</li>
 *   <li>复用 {@link PackageMiner} 从交易池打包交易</li>
 *   <li>构造区块头（prevHash / height / time / merkleRoot / nBits）</li>
 *   <li>用节点 Ed25519 私钥签名区块头，签名写入 {@code nNonce}(r) + blockNotice(s)</li>
 *   <li>通过 {@link ApplicationContext} 发布 {@link NewBlockMinedEvent} 触发 P2P 广播</li>
 * </ol>
 *
 * <h3>校验链</h3>
 * <ol>
 *   <li>区块非空、coinbase 存在</li>
 *   <li>提案者 ∈ 活跃验证人集合（{@link ValidatorRegistry}）</li>
 *   <li>质押满足门槛（{@link StakingService}）</li>
 *   <li>时间窗口合法（区块时间在合理范围内）</li>
 *   <li>提案者未被罚没（{@link SlashingService} / {@link ValidatorStatus}）</li>
 *   <li>区块签名验证（Ed25519 公钥验签，<b>fail-closed</b>）：公钥缺失、
 *       验签失败、验签异常一律拒绝，不存在"降级放行"路径
 *       （v1.9.3 安全修复：此前任意伪造区块均可通过校验）</li>
 * </ol>
 *
 * <h3>密钥绑定（安全约束）</h3>
 * <p>节点签名密钥必须与某个已注册 Validator 的公钥对应，否则本节点
 * {@link #propose()} 恒返回 null（拒绝出块），产出的区块也无法通过其他
 * 节点的 fail-closed 验签。当前无参构造器默认生成随机密钥，生产部署必须
 * 通过 {@link #PosConsensusEngine(KeyPair, long)} 显式注入与注册验证人对应
 * 的密钥（keystore 加载接线为后续步骤）。</p>
 *
 * @since 1.2
 */
@Component
@ConditionalOnProperty(name = "nexus.consensus.mode", havingValue = "pos")
public class PosConsensusEngine implements PosConsensus {

    private static final Logger logger = LoggerFactory.getLogger(PosConsensusEngine.class);

    /** 默认出块时间窗口（秒），与 nexus.consensus.block-interval 对齐 */
    private static final long DEFAULT_BLOCK_INTERVAL_SECONDS = 30L;

    /** 允许的区块时间漂移（秒），用于校验时间窗口 */
    private static final long MAX_TIME_DRIFT_SECONDS = 10L;

    @Autowired
    private ValidatorRegistry validatorRegistry;

    @Autowired
    private StakingService stakingService;

    @Autowired
    private PosProposer proposer;

    @Autowired
    private PosRewardDistributor rewardDistributor;

    @Autowired
    private SlashingService slashingService;

    @Autowired
    private StateDB stateDB;

    @Autowired
    private NexusChainBlockChain bc;

    @Autowired
    private PackageMiner packageMiner;

    @Autowired
    private MerkleRule merkleRule;

    @Autowired
    private EconomicModel economicModel;

    @Autowired
    private ConsensusConfig consensusConfig;

    @Autowired
    private ApplicationContext applicationContext;

    /** 节点签名密钥对（Ed25519），用于对区块头签名 */
    private final KeyPair signingKeyPair;

    /** 出块时间窗口（秒） */
    private final long blockIntervalSeconds;

    public PosConsensusEngine() {
        this(loadConfiguredOrRandomKeyPair(), DEFAULT_BLOCK_INTERVAL_SECONDS);
    }

    /**
     * 从配置加载或生成节点签名密钥（#20：生产固定密钥，多节点可预生成验证人）。
     *
     * <p>配置项 {@code nexus.consensus.validator-private-key}（Ed25519 私钥 hex，
     * 32 字节）——提供时用配置密钥（节点重启后地址稳定，可预先注册验证人并
     * 加入多节点拓扑）；未配置时随机生成（开发/测试默认行为）。</p>
     */
    private static KeyPair loadConfiguredOrRandomKeyPair() {
        String configured = System.getProperty("nexus.consensus.validator-private-key");
        if (configured == null || configured.isEmpty()) {
            return KeyPair.generateEd25519KeyPair();
        }
        try {
            byte[] privateKey = org.apache.commons.codec.binary.Hex.decodeHex(configured);
            // Ed25519 公钥 = 私钥标量 × 基点（BC Ed25519PrivateKeyParameters.generatePublicKey）
            org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters bcPriv =
                    new org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(privateKey, 0);
            byte[] publicKey = bcPriv.generatePublicKey().getEncoded();
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(PosConsensusEngine.class)
                    .warn("validator-private-key parse failed, falling back to random: {}", e.getMessage());
            return KeyPair.generateEd25519KeyPair();
        }
    }

    public PosConsensusEngine(KeyPair signingKeyPair, long blockIntervalSeconds) {
        this.signingKeyPair = signingKeyPair;
        this.blockIntervalSeconds = blockIntervalSeconds;
        logger.info("PosConsensusEngine initialized with Ed25519 signing key, public key = {}",
                Hex.encodeHexString(signingKeyPair.getPublicKey().getBytes()));
    }

    /**
     * 发起新区块提案。
     *
     * <p>真实出块流程：定位本节点验证人身份 → 选取提案者 → 非本节点轮次则跳过 →
     * 打包交易 → 构造区块 → 签名（失败即放弃）→ 广播。</p>
     *
     * <p>fail-closed 约束：本节点签名密钥未绑定任何已注册验证人时返回 {@code null}；
     * 本轮被选中的提案者不是本节点时返回 {@code null}（避免用自己的密钥替其他
     * 验证人签名，产出无法通过验签的区块）。</p>
     *
     * @return 提案区块；非本节点轮次、密钥未绑定验证人、无可用提案者、
     *         父区块缺失或签名失败时返回 null
     */
    @Override
    public Block propose() {
        // 0. 按签名公钥定位本节点验证人身份（fail-closed：未绑定则拒绝出块）
        Validator self = findValidatorByPublicKeyHex(
                Hex.encodeHexString(signingKeyPair.getPublicKey().getBytes()));
        if (self == null) {
            logger.warn("Propose rejected: node signing key is not bound to any registered validator");
            return null;
        }

        // 1. 获取父区块，计算新区块高度
        Block parent = stateDB.getBestBlock();
        if (parent == null) {
            logger.warn("Propose failed: no parent block available from StateDB");
            return null;
        }
        long height = parent.nHeight + 1;

        // 2. 选取当前提案者（stake-weighted random 或 round-robin，PLAN-001 步骤 4）
        Validator selected = selectProposerWithStrategy(height);
        if (selected == null) {
            logger.warn("Propose failed: no proposer selected at height {}", height);
            return null;
        }

        // 3. 轮次判定：仅当被选中的提案者是本节点时才出块（fail-closed）
        if (!self.getAddress().equals(selected.getAddress())) {
            logger.debug("Not this node's slot at height {}: selected={}, self={}",
                    height, selected.getAddress(), self.getAddress());
            return null;
        }

        logger.info("Proposing block at height {} by proposer {}", height, selected.getAddress());

        try {
            // 4. 构造区块
            Block block = buildBlock(parent, height, selected);
            if (block == null) {
                logger.warn("Propose failed: block construction failed at height {}", height);
                return null;
            }

            // 5. 提案者签名（用节点 Ed25519 私钥签名区块头；失败即放弃，不产出无法验证的区块）
            if (!signBlock(block)) {
                logger.error("Propose aborted at height {}: block signing failed (fail-closed, no fallback)",
                        height);
                return null;
            }

            // 6. P2P 广播区块（通过 Spring 事件触发 SyncManager 广播）
            broadcastBlock(block);

            // 6.1 本地采纳（单节点/自产自收闭环）：写回链推进高度，
            //     否则 stateDB.getBestBlock() 恒为 genesis、高度永不递增，
            //     epoch 边界（finality 投票）永不触发（ADR-031 决策 8 补充修复）
            try {
                if (stateDB != null && block.nHeight > 0) {
                    stateDB.writeBlock(block);
                }
            } catch (Exception e) {
                logger.warn("Local adoption failed at height {}: {}", height, e.getMessage());
            }

            // 7. 分配出块奖励
            BigDecimal fees = computeBlockFees(block);
            rewardDistributor.distributeBlockReward(selected.getAddress(), fees);

            logger.info("Block proposed at height {} hash={} by proposer {}",
                    height, block.getHashHexString(), selected.getAddress());
            return block;
        } catch (Exception e) {
            logger.error("Propose failed at height {}: {}", height, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 验证区块合法性。
     *
     * <p>完整校验链：提案者 ∈ 验证人 → 质押门槛 → 时间窗口 → 未罚没 → 签名验证。</p>
     *
     * @param block 待验证区块
     * @return 验证通过返回 true；任一校验失败返回 false
     */
    @Override
    public boolean validate(Block block) {
        if (block == null) {
            logger.debug("Validate failed: block is null");
            return false;
        }
        if (block.body == null || block.body.isEmpty()) {
            logger.debug("Validate failed: block body empty at height {}", block.nHeight);
            return false;
        }

        // 1. 从 coinbase tx 提取提案者 pubkeyHash
        Transaction coinbase = block.body.get(0);
        if (coinbase == null || coinbase.to == null) {
            logger.debug("Validate failed: coinbase tx or recipient missing at height {}", block.nHeight);
            return false;
        }
        String proposerPubKeyHash = Hex.encodeHexString(coinbase.to);

        // 2. 通过 pubkeyHash 查找提案者（遍历验证人集合匹配）
        Validator proposerValidator = findValidatorByPubKeyHash(proposerPubKeyHash);
        if (proposerValidator == null) {
            logger.warn("Validate failed: proposer {} not in validator registry at height {}",
                    proposerPubKeyHash, block.nHeight);
            return false;
        }

        // 3. 校验提案者 ∈ 活跃验证人集合
        if (proposerValidator.getStatus() != ValidatorStatus.ACTIVE) {
            logger.warn("Validate failed: proposer {} is not ACTIVE (status={}) at height {}",
                    proposerPubKeyHash, proposerValidator.getStatus(), block.nHeight);
            return false;
        }

        // 4. 校验质押满足门槛
        BigDecimal stake = stakingService.getStake(proposerValidator.getAddress());
        BigDecimal minStake = validatorRegistry.getMinStakeAmount();
        if (stake.compareTo(minStake) < 0) {
            logger.warn("Validate failed: proposer {} stake {} below minimum {} at height {}",
                    proposerPubKeyHash, stake, minStake, block.nHeight);
            return false;
        }

        // 5. 校验时间窗口合法
        if (!validateTimeWindow(block)) {
            logger.warn("Validate failed: invalid time window at height {}, nTime={}",
                    block.nHeight, block.nTime);
            return false;
        }

        // 6. 校验提案者未被罚没（状态非 SLASHED 已在步骤 3 校验，这里再次确认）
        if (proposerValidator.getStatus() == ValidatorStatus.SLASHED) {
            logger.warn("Validate failed: proposer {} has been slashed at height {}",
                    proposerPubKeyHash, block.nHeight);
            return false;
        }

        // 7. 校验区块签名（Ed25519 公钥验签）
        if (!verifyBlockSignature(block, proposerValidator)) {
            logger.warn("Validate failed: signature verification failed at height {}", block.nHeight);
            return false;
        }

        logger.debug("Block validated successfully at height {} by proposer {}",
                block.nHeight, proposerPubKeyHash);
        return true;
    }

    /**
     * 对作恶验证者执行惩罚。
     *
     * @param validator 被惩罚的验证者
     */
    @Override
    public void slash(Validator validator) {
        if (validator == null) {
            return;
        }
        slashingService.slash(validator.getAddress(), SlashingService.Offense.MALICIOUS);
    }

    /**
     * 对指定验证者执行指定类型的惩罚。
     *
     * @param validator 验证者
     * @param offense   违规类型
     * @return 罚没金额
     */
    public BigDecimal slash(Validator validator, SlashingService.Offense offense) {
        if (validator == null) {
            return BigDecimal.ZERO;
        }
        return slashingService.slash(validator.getAddress(), offense);
    }

    /**
     * 选取指定高度的提案者。
     *
     * @param height 区块高度
     * @return 提案者验证人
     */
    public Validator selectProposer(long height) {
        return proposer.selectProposer(height);
    }

    /**
     * 按配置选择提案者（PLAN-001 步骤 4）：
     * {@code nexus.consensus.proposer-strategy}：
     * <ul>
     *   <li>{@code random}（默认）：权益加权随机——单节点/测试语义</li>
     *   <li>{@code round-robin}：{@code height % 活跃验证人数} 确定性轮换——
     *       多节点共享验证人集合时全网对同一高度得出同一 proposer，
     *       避免多节点同时出块冲突</li>
     * </ul>
     */
    private Validator selectProposerWithStrategy(long height) {
        String strategy = System.getProperty("nexus.consensus.proposer-strategy", "random");
        if ("round-robin".equalsIgnoreCase(strategy)) {
            Validator v = proposer.selectRoundRobinProposer(height);
            if (v != null) {
                logger.debug("Round-robin proposer at height {}: {}", height, v.getAddress());
            }
            return v;
        }
        return proposer.selectProposer(height);
    }

    /**
     * 向出块者分配奖励。
     *
     * @param proposerAddress 出块者地址
     * @param fees            交易手续费
     * @return 分配奖励金额
     */
    public BigDecimal rewardProposer(String proposerAddress, BigDecimal fees) {
        return rewardDistributor.distributeBlockReward(proposerAddress, fees);
    }

    // ==================== 出块辅助方法 ====================

    /**
     * 构造新区块（复用 PackageMiner 打包逻辑）。
     */
    private Block buildBlock(Block parent, long height, Validator selectedProposer) throws Exception {
        Block block = new Block();
        block.nVersion = parent.nVersion;
        block.hashPrevBlock = parent.getHash();
        block.nHeight = height;
        block.nTime = System.currentTimeMillis() / 1000;
        block.nBits = parent.nBits;
        block.nNonce = new byte[Block.HASH_SIZE];
        block.blockNotice = new byte[Block.MAX_NOTICE_LENGTH];
        block.body = new ArrayList<>();

        // 添加 coinbase 交易（收款人为提案者 pubkeyHash）
        Transaction coinbase = createCoinBase(height, selectedProposer);
        block.body.add(coinbase);

        // 从交易池打包交易（复用 PackageMiner）
        List<Transaction> packed;
        try {
            packed = packageMiner.TransferCheck(parent.getHash(), height, block);
        } catch (Exception e) {
            logger.warn("PackageMiner.TransferCheck failed at height {}: {}", height, e.getMessage());
            packed = new ArrayList<>();
        }

        // 累加手续费到 coinbase
        for (Transaction tx : packed) {
            coinbase.amount += tx.getFee();
            block.body.add(tx);
        }

        // 计算 coinbase tx hash
        coinbase.setHashCache(HashUtil.keccak256(coinbase.getRawForHash()));

        // 计算 merkle root
        block.hashMerkleRoot = Block.calculateMerkleRoot(block.body);
        block.hashMerkleState = new byte[Block.HASH_SIZE];
        block.hashMerkleIncubate = new byte[Block.HASH_SIZE];

        return block;
    }

    /**
     * 创建 coinbase 交易（收款人为提案者）。
     */
    private Transaction createCoinBase(long height, Validator proposer) {
        Transaction tx = Transaction.createEmpty();
        tx.amount = economicModel.getConsensusRewardAtHeight(height);
        // 提案者 address → pubkeyHash
        try {
            tx.to = org.nexus.keystore.wallet.KeystoreAction.addressToPubkeyHash(proposer.getAddress());
        } catch (Exception e) {
            logger.warn("Failed to convert proposer address to pubkeyHash: {}", e.getMessage());
            tx.to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
        }
        return tx;
    }

    /**
     * 计算区块包含的交易手续费总额。
     */
    private BigDecimal computeBlockFees(Block block) {
        BigDecimal fees = BigDecimal.ZERO;
        if (block.body == null || block.body.size() <= 1) {
            return fees;
        }
        // 跳过 coinbase（index 0）
        for (int i = 1; i < block.body.size(); i++) {
            fees = fees.add(BigDecimal.valueOf(block.body.get(i).getFee()));
        }
        return fees;
    }

    /**
     * 对区块头签名（Ed25519）。
     *
     * <p>签名写入 {@code nNonce}(r, 32 字节) + {@code blockNotice}(s, 32 字节)。
     * 签名消息为"除 nNonce 和 blockNotice 外的区块头"。</p>
     *
     * <p>v1.9.3 安全修复：签名失败不再写入哈希指纹 fallback（该 fallback 产出
     * 永远无法通过真实验签的区块），而是返回 {@code false} 由调用方放弃出块。</p>
     *
     * @return 签名成功返回 true；失败返回 false（fail-closed）
     */
    private boolean signBlock(Block block) {
        try {
            byte[] signingData = getSigningData(block);
            Ed25519DsaSigner signer = new Ed25519DsaSigner(signingKeyPair.getPrivateKey());
            Signature signature = signer.sign(signingData);
            // r → nNonce（32 字节），s → blockNotice（32 字节）
            block.nNonce = signature.getBinaryR();
            byte[] s = signature.getBinaryS();
            // blockNotice 长度为 MAX_NOTICE_LENGTH(32)，s 也是 32 字节
            block.blockNotice = s;
            logger.debug("Block signed at height {}, signature r={} s={}",
                    block.nHeight,
                    Hex.encodeHexString(block.nNonce),
                    Hex.encodeHexString(block.blockNotice));
            return true;
        } catch (Exception e) {
            logger.error("Sign block failed at height {}: {}", block.nHeight, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 广播区块（通过 Spring 事件触发 SyncManager 的 P2P 广播）。
     */
    private void broadcastBlock(Block block) {
        if (applicationContext == null) {
            logger.warn("ApplicationContext not available, skip broadcast");
            return;
        }
        try {
            applicationContext.publishEvent(new NewBlockMinedEvent(this, block));
            logger.debug("Block broadcast event published at height {}", block.nHeight);
        } catch (Exception e) {
            logger.error("Broadcast block failed at height {}: {}", block.nHeight, e.getMessage(), e);
        }
    }

    // ==================== 校验辅助方法 ====================

    /**
     * 通过公钥（hex）查找验证人，用于定位本节点的验证人身份。
     *
     * @param publicKeyHex 公钥十六进制表示
     * @return 公钥匹配的验证人；未找到返回 null
     */
    private Validator findValidatorByPublicKeyHex(String publicKeyHex) {
        if (publicKeyHex == null || publicKeyHex.isEmpty()) {
            return null;
        }
        for (Validator v : validatorRegistry.getAllValidators()) {
            if (publicKeyHex.equalsIgnoreCase(v.getPublicKey())) {
                return v;
            }
        }
        return null;
    }

    /**
     * 通过 pubkeyHash 查找验证人。
     */
    private Validator findValidatorByPubKeyHash(String pubKeyHashHex) {
        if (pubKeyHashHex == null) {
            return null;
        }
        for (Validator v : validatorRegistry.getAllValidators()) {
            try {
                byte[] vPubKeyHash = org.nexus.keystore.wallet.KeystoreAction.addressToPubkeyHash(v.getAddress());
                if (pubKeyHashHex.equals(Hex.encodeHexString(vPubKeyHash))) {
                    return v;
                }
            } catch (Exception e) {
                // ignore conversion errors
            }
        }
        return null;
    }

    /**
     * 校验时间窗口。
     *
     * <p>区块时间应在 [now - drift, now + drift] 范围内，
     * 且大于父区块时间。</p>
     */
    private boolean validateTimeWindow(Block block) {
        long now = System.currentTimeMillis() / 1000;
        long blockTime = block.nTime;
        if (blockTime <= 0) {
            return false;
        }
        // 允许一定的时间漂移（前后 MAX_TIME_DRIFT_SECONDS 秒）
        if (blockTime < now - MAX_TIME_DRIFT_SECONDS - blockIntervalSeconds) {
            logger.debug("Block time {} too far in past (now={})", blockTime, now);
            return false;
        }
        if (blockTime > now + MAX_TIME_DRIFT_SECONDS) {
            logger.debug("Block time {} too far in future (now={})", blockTime, now);
            return false;
        }
        return true;
    }

    /**
     * 验证区块签名。
     *
     * <p>从 nNonce(r) + blockNotice(s) 重构 Signature，
     * 用提案者公钥验签。签名消息为"除 nNonce 和 blockNotice 外的区块头"。</p>
     *
     * <p><b>fail-closed（v1.9.3 安全修复）</b>：提案者公钥缺失、Ed25519 验签失败、
     * 验签异常三条路径一律返回 {@code false}。此前这三条路径"降级为 nNonce 非零
     * 校验"并返回 true，导致 PoS 模式下任意伪造区块都能通过共识校验。</p>
     */
    private boolean verifyBlockSignature(Block block, Validator proposerValidator) {
        // nNonce 必须非零
        if (block.nNonce == null || Arrays.areAllZeroes(block.nNonce, 0, block.nNonce.length)) {
            logger.debug("Signature check failed: nNonce is all zeroes at height {}", block.nHeight);
            return false;
        }

        // fail-closed：提案者公钥缺失则无法验签，直接拒绝
        String publicKeyHex = proposerValidator.getPublicKey();
        if (publicKeyHex == null || publicKeyHex.isEmpty()) {
            logger.warn("Signature check failed (fail-closed): proposer public key missing at height {}",
                    block.nHeight);
            return false;
        }

        try {
            // 从 nNonce + blockNotice 重构签名
            if (block.blockNotice == null || block.blockNotice.length != Block.MAX_NOTICE_LENGTH) {
                logger.debug("Signature check failed: blockNotice invalid at height {}", block.nHeight);
                return false;
            }
            byte[] sigBytes = Arrays.concatenate(block.nNonce, block.blockNotice);
            Signature signature = new Signature(sigBytes);

            // 用提案者公钥验签
            byte[] pubKeyBytes = Hex.decodeHex(publicKeyHex.toCharArray());
            org.nexus.keystore.crypto.PublicKey pubKey =
                    new org.nexus.keystore.crypto.PublicKey(pubKeyBytes);
            Ed25519DsaSigner verifier = new Ed25519DsaSigner(pubKey);

            byte[] signingData = getSigningData(block);
            boolean valid = verifier.verify(signingData, signature);
            if (!valid) {
                // fail-closed：验签失败直接拒绝
                logger.warn("Signature check failed (fail-closed): Ed25519 verify failed at height {}",
                        block.nHeight);
            }
            return valid;
        } catch (Exception e) {
            // fail-closed：验签异常直接拒绝
            logger.warn("Signature check failed (fail-closed): verification exception at height {}: {}",
                    block.nHeight, e.getMessage());
            return false;
        }
    }

    /**
     * 计算区块头的待签名数据（除 nNonce 和 blockNotice 外）。
     */
    private byte[] getSigningData(Block block) {
        return Arrays.concatenate(
                new byte[][]{
                        BigEndian.encodeUint32(block.nVersion),
                        block.hashPrevBlock == null ? new byte[Block.HASH_SIZE] : block.hashPrevBlock,
                        block.hashMerkleRoot == null ? new byte[Block.HASH_SIZE] : block.hashMerkleRoot,
                        block.hashMerkleState == null ? new byte[Block.HASH_SIZE] : block.hashMerkleState,
                        block.hashMerkleIncubate == null ? new byte[Block.HASH_SIZE] : block.hashMerkleIncubate,
                        BigEndian.encodeUint32(block.nHeight),
                        BigEndian.encodeUint32(block.nTime),
                        block.nBits == null ? new byte[Block.HASH_SIZE] : block.nBits
                });
    }

    // ==================== Getter ====================

    public ValidatorRegistry getValidatorRegistry() {
        return validatorRegistry;
    }

    public StakingService getStakingService() {
        return stakingService;
    }

    public PosProposer getProposer() {
        return proposer;
    }

    public PosRewardDistributor getRewardDistributor() {
        return rewardDistributor;
    }

    public SlashingService getSlashingService() {
        return slashingService;
    }

    public KeyPair getSigningKeyPair() {
        return signingKeyPair;
    }

    public long getBlockIntervalSeconds() {
        return blockIntervalSeconds;
    }
}
