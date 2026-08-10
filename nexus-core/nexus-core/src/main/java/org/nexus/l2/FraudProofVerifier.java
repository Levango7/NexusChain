package org.nexus.l2;

import org.nexus.l2.challenge.ChallengeConflictResolver;
import org.nexus.l2.challenge.ChallengeConflictResult;
import org.nexus.l2.challenge.ChallengePeriodPolicy;
import org.nexus.consensus.pos.SlashingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 欺诈证明验证器。
 *
 * <p>用于 Optimistic Rollup 的挑战窗口管理与欺诈证明校验。
 * 支持两种验证模式：</p>
 * <ul>
 *   <li>整批验证（旧）：{@link #verifyFraudProof(long, String, String, RollupBatch)}</li>
 *   <li>单步二分验证（新）：{@link #verifyFraudProof(FraudProof)}，证明尺寸 O(log n)</li>
 * </ul>
 *
 * <p>挑战者需先 {@link #stakeChallengeBond} 质押 bond，再提交欺诈证明。
 * 挑战成功奖励挑战者；失败罚没 bond。挑战期结束后可 {@link #finalizeBatch}
 * 标记 VERIFIED 并触发提款解锁。</p>
 *
 * @since 1.2
 */
@Component
public class FraudProofVerifier {

    private static final Logger logger = LoggerFactory.getLogger(FraudProofVerifier.class);

    /** 默认挑战窗口：7 天 */
    private static final Duration DEFAULT_CHALLENGE_WINDOW = Duration.ofDays(7);

    /** 默认挑战奖励比例：罚没金额的 50% */
    private static final BigDecimal DEFAULT_REWARD_RATE = new BigDecimal("0.5");

    @Autowired
    private StateRootManager stateRootManager;

    @Autowired(required = false)
    private SlashingService slashingService;

    @Autowired(required = false)
    private DefaultL2BridgeContract bridgeContract;

    /** 多挑战者冲突解决器（first-valid-wins，1.3 新增） */
    @Autowired(required = false)
    private ChallengeConflictResolver conflictResolver;

    /** 挑战期动态策略（高价值延长 + 可疑行为延长，1.3 新增） */
    @Autowired(required = false)
    private ChallengePeriodPolicy challengePeriodPolicy;

    private final Duration challengeWindow;
    private final BigDecimal rewardRate;

    /** 批次 ID -> 提交时间 */
    private final Map<Long, Instant> batchSubmitTime = new ConcurrentHashMap<>();

    /** 批次 ID -> 批次实体（用于挑战与 finalize） */
    private final Map<Long, RollupBatch> batchStore = new ConcurrentHashMap<>();

    /** 批次 ID -> 提交者地址 */
    private final Map<Long, String> submitterStore = new ConcurrentHashMap<>();

    /** 挑战者 bond：challengerId -> bond */
    private final Map<String, ChallengeBond> challengeBonds = new ConcurrentHashMap<>();

    /** 批次 ID -> 挑战者地址（已成功挑战） */
    private final Map<Long, String> challengerStore = new ConcurrentHashMap<>();

    public FraudProofVerifier() {
        this(DEFAULT_CHALLENGE_WINDOW, DEFAULT_REWARD_RATE);
    }

    public FraudProofVerifier(Duration challengeWindow) {
        this(challengeWindow, DEFAULT_REWARD_RATE);
    }

    public FraudProofVerifier(Duration challengeWindow, BigDecimal rewardRate) {
        this.challengeWindow = challengeWindow;
        this.rewardRate = rewardRate;
    }

    /**
     * 批次提交时调用，开启挑战窗口。
     */
    public void onSubmit(long batchId) {
        batchSubmitTime.put(batchId, Instant.now());
        logger.info("Challenge window opened for batch {} (duration={})", batchId, challengeWindow);
    }

    /**
     * 批次提交时调用，记录批次实体与提交者，并开启挑战窗口。
     *
     * @param batch     批次
     * @param submitter 提交者地址
     */
    public void onSubmit(RollupBatch batch, String submitter) {
        if (batch == null) {
            return;
        }
        long batchId = batch.getBatchId();
        batchStore.put(batchId, batch);
        submitterStore.put(batchId, submitter);
        onSubmit(batchId);
    }

    /**
     * 验证整批欺诈证明（旧接口，保留兼容）。
     */
    public boolean verifyFraudProof(long batchId, String claimedPrevRoot, String claimedNewRoot, RollupBatch batch) {
        if (batch == null) {
            logger.warn("Fraud proof rejected: no batch found for {}", batchId);
            return false;
        }
        boolean validTransition = stateRootManager.verifyTransition(claimedPrevRoot, claimedNewRoot, batch);
        if (!validTransition) {
            logger.info("Fraud proof VALID for batch {}: state transition mismatch", batchId);
            return true;
        }
        logger.info("Fraud proof REJECTED for batch {}: transition is valid", batchId);
        return false;
    }

    /**
     * 验证单步二分欺诈证明（O(log n) 证明尺寸）。
     *
     * <p>验证流程：</p>
     * <ol>
     *   <li>MerkleVerify(proof.merkleProof, proof.prevRoot) 确认 tx 隶属批次</li>
     *   <li>recomputed = applyTx(proof.stateBefore, proof.tx) 重算单步</li>
     *   <li>若 recomputed != proof.claimedStateAfter 则欺诈成立</li>
     * </ol>
     *
     * @param proof 欺诈证明
     * @return 欺诈成立返回 true；证明无效或批次合法返回 false
     */
    public boolean verifyFraudProof(FraudProof proof) {
        if (proof == null) {
            logger.warn("Fraud proof rejected: null proof");
            return false;
        }
        long batchId = proof.getBatchId();
        // a. 验证 Merkle 证明：tx 隶属于批次
        MerkleProof mp = proof.getMerkleProof();
        if (mp == null) {
            logger.warn("Fraud proof rejected for batch {}: no merkle proof", batchId);
            return false;
        }
        if (!MerklePatriciaTrie.verifyProof(mp, proof.getPrevRoot())) {
            logger.warn("Fraud proof rejected for batch {}: merkle proof invalid", batchId);
            return false;
        }
        // b. 重算单步状态转换
        String recomputed = StateRootManager.applyTx(proof.getStateBefore(), proof.getTx());
        // c. 比较声明的状态后根
        if (proof.getClaimedStateAfter() == null
                || !recomputed.equals(proof.getClaimedStateAfter())) {
            logger.info("Fraud proof VALID for batch {} txIndex {}: recomputed={} claimed={}",
                    batchId, proof.getTxIndex(), recomputed, proof.getClaimedStateAfter());
            return true;
        }
        logger.info("Fraud proof REJECTED for batch {} txIndex {}: single step consistent",
                batchId, proof.getTxIndex());
        return false;
    }

    /**
     * 挑战者本地重算批次，二分定位出错步 k，构造单步欺诈证明。
     *
     * <p>假设提交者声明的状态根链为 claimedRoots[0..n]，挑战者重算得 actualRoots[0..n]。
     * 二分找到首个 actualRoots[k+1] != claimedRoots[k+1] 的位置，构造证明。</p>
     *
     * @param batchId       批次 ID
     * @param challenger    挑战者地址
     * @param claimedRoots  提交者声明的递归根链（长度 n+1，claimedRoots[0]=prevRoot）
     * @return 欺诈证明；批次合法或无法定位返回 null
     */
    public FraudProof generateFraudProof(long batchId, String challenger, List<String> claimedRoots) {
        StateRootManager.BatchContext ctx = stateRootManager.getBatchContext(batchId);
        if (ctx == null || ctx.txs == null || ctx.txs.isEmpty()) {
            logger.warn("Cannot generate fraud proof for batch {}: no context", batchId);
            return null;
        }
        List<String> actualRoots = ctx.recursiveRoots;
        int n = ctx.txs.size();
        if (claimedRoots == null || claimedRoots.size() != n + 1) {
            logger.warn("Cannot generate fraud proof for batch {}: claimedRoots size mismatch", batchId);
            return null;
        }
        // 二分定位首个出错步
        int k = binarySearchMismatch(actualRoots, claimedRoots, 0, n);
        if (k < 0) {
            logger.info("Batch {} is valid, no fraud proof generated", batchId);
            return null;
        }
        L2Transaction tx = ctx.txs.get(k);
        MerkleProof mp = stateRootManager.getMerkleProof(batchId, k);
        FraudProof proof = new FraudProof();
        proof.setBatchId(batchId);
        proof.setPrevRoot(ctx.batchTxRoot);
        proof.setTxIndex(k);
        proof.setTx(tx);
        proof.setStateBefore(actualRoots.get(k));
        proof.setMerkleProof(mp);
        proof.setStateAfter(actualRoots.get(k + 1));
        proof.setClaimedStateAfter(claimedRoots.get(k + 1));
        proof.setChallenger(challenger);
        ChallengeBond bond = challengeBonds.get(challenger);
        if (bond != null) {
            proof.setChallengeBond(bond.getAmount());
        }
        logger.info("Fraud proof generated for batch {} at txIndex {} (challenger={})",
                batchId, k, challenger);
        return proof;
    }

    /**
     * 二分查找首个 actualRoots[i+1] != claimedRoots[i+1] 的位置 i。
     * 前置：actualRoots[0] == claimedRoots[0]（同一 prevRoot）。
     */
    private int binarySearchMismatch(List<String> actual, List<String> claimed, int lo, int hi) {
        // 在 [lo, hi] 中找首个 i 满足 actual[i+1] != claimed[i+1]
        // 前置：actual[lo] == claimed[lo]
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (actual.get(mid + 1).equals(claimed.get(mid + 1))) {
                // mid+1 一致，出错在 (mid+1, hi]
                lo = mid + 1;
            } else {
                // mid+1 不一致，出错在 [lo, mid]
                hi = mid;
            }
        }
        if (lo >= actual.size() - 1) {
            return -1;
        }
        return actual.get(lo + 1).equals(claimed.get(lo + 1)) ? -1 : lo;
    }

    /**
     * 判断指定批次的挑战窗口是否已结束。
     *
     * <p>自 1.3 起，若 {@link ChallengePeriodPolicy} 可用则使用动态挑战期
     * （高价值延长 + 可疑行为延长），否则回退到固定 {@code challengeWindow}。</p>
     */
    public boolean isChallengeWindowOver(long batchId) {
        Instant submit = batchSubmitTime.get(batchId);
        if (submit == null) {
            return false;
        }
        if (challengePeriodPolicy != null) {
            return challengePeriodPolicy.isChallengeWindowOver(batchId, submit, computeBatchValue(batchId));
        }
        return Instant.now().isAfter(submit.plus(challengeWindow));
    }

    /**
     * 提交挑战（first-valid-wins 多挑战者冲突解决）。
     *
     * <p>自 1.3 起支持多挑战者对同一批次提交欺诈证明：</p>
     * <ol>
     *   <li>校验挑战者 bond 已质押</li>
     *   <li>校验挑战窗口未关闭</li>
     *   <li>验证欺诈证明</li>
     *   <li>委托 {@link ChallengeConflictResolver} 解决冲突：
     *     <ul>
     *       <li>FIRST_VALID：首个有效证明生效 → markChallenged + slashSubmitter + rewardChallenger</li>
     *       <li>DUPLICATE_AFTER_VALID：bond 退还（不罚没，鼓励竞争）</li>
     *       <li>INVALID_PROOF：bond 罚没</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * <p>若 {@link ChallengeConflictResolver} 未注入，回退到旧逻辑
     * （首个挑战直接生效，无冲突解决）。</p>
     *
     * @param proof 欺诈证明
     * @return 冲突解决结果；batch 不存在返回 BATCH_NOT_FOUND，无 bond 返回 NO_BOND
     */
    public ChallengeConflictResult submitChallenge(FraudProof proof) {
        if (proof == null) {
            return ChallengeConflictResult.INVALID_PROOF;
        }
        long batchId = proof.getBatchId();
        String challenger = proof.getChallenger();

        RollupBatch batch = batchStore.get(batchId);
        if (batch == null) {
            logger.warn("submitChallenge: batch {} not found", batchId);
            return ChallengeConflictResult.BATCH_NOT_FOUND;
        }

        ChallengeBond bond = challengeBonds.get(challenger);
        if (bond == null || bond.getStatus() != ChallengeBond.Status.STAKED) {
            logger.warn("submitChallenge: challenger {} has no staked bond", challenger);
            return ChallengeConflictResult.NO_BOND;
        }

        boolean windowClosed = isChallengeWindowOver(batchId);
        boolean proofValid = verifyFraudProof(proof);

        // 无冲突解决器：回退到旧逻辑
        if (conflictResolver == null) {
            return legacyChallenge(batchId, challenger, bond, batch, proofValid, windowClosed);
        }

        // first-valid-wins 冲突解决
        ChallengeConflictResult result = conflictResolver.resolveChallenge(
                batchId, challenger, proofValid, windowClosed);

        switch (result) {
            case FIRST_VALID:
                conflictResolver.recordFirstValidProof(batchId, proof);
                executeChallengeSuccess(batchId, challenger, bond, batch);
                break;
            case DUPLICATE_AFTER_VALID:
                // 后续有效挑战者 bond 退还（不罚没）
                bond.setStatus(ChallengeBond.Status.RELEASED);
                logger.info("submitChallenge: challenger {} bond refunded (first-valid-wins, firstValid={})",
                        challenger, conflictResolver.getFirstValidChallenger(batchId));
                break;
            case INVALID_PROOF:
                bond.setStatus(ChallengeBond.Status.SLASHED);
                logger.info("submitChallenge: challenger {} bond slashed (invalid proof)", challenger);
                break;
            case WINDOW_CLOSED:
                logger.info("submitChallenge: challenger {} rejected (window closed)", challenger);
                break;
            default:
                break;
        }
        return result;
    }

    /**
     * 旧挑战逻辑（无冲突解决器时的回退路径）。
     */
    private ChallengeConflictResult legacyChallenge(long batchId, String challenger,
                                                    ChallengeBond bond, RollupBatch batch,
                                                    boolean proofValid, boolean windowClosed) {
        if (windowClosed) {
            return ChallengeConflictResult.WINDOW_CLOSED;
        }
        if (!proofValid) {
            bond.setStatus(ChallengeBond.Status.SLASHED);
            return ChallengeConflictResult.INVALID_PROOF;
        }
        executeChallengeSuccess(batchId, challenger, bond, batch);
        return ChallengeConflictResult.FIRST_VALID;
    }

    /**
     * 执行挑战成功副作用：markChallenged + slashSubmitter + rewardChallenger。
     */
    private void executeChallengeSuccess(long batchId, String challenger,
                                          ChallengeBond bond, RollupBatch batch) {
        markChallenged(batch);
        String submitter = submitterStore.get(batchId);
        BigDecimal slashAmount = new BigDecimal("1000");
        BigDecimal actualSlashed = slashSubmitter(batchId, submitter, slashAmount);
        BigDecimal reward = bond.getAmount().add(actualSlashed.multiply(rewardRate));
        rewardChallenger(challenger, reward);
        logger.info("Challenge SUCCESS for batch {}: submitter {} slashed {}, challenger {} rewarded {}",
                batchId, submitter, actualSlashed, challenger, reward);
    }

    /**
     * 报告可疑行为，延长批次挑战期。
     *
     * <p>检测到 sequencer 提交多个冲突 state root、隐藏交易、伪造 Merkle 证明等
     * 可疑行为时调用。委托给 {@link ChallengePeriodPolicy} 延长挑战窗口。</p>
     *
     * @param batchId 批次 ID
     * @param reason  可疑行为描述
     * @return 是否成功延长；ChallengePeriodPolicy 未注入返回 false
     */
    public boolean reportSuspiciousActivity(long batchId, String reason) {
        if (challengePeriodPolicy == null) {
            logger.warn("reportSuspiciousActivity: ChallengePeriodPolicy not available, batch {}", batchId);
            return false;
        }
        challengePeriodPolicy.reportSuspiciousActivity(batchId, reason);
        return true;
    }

    /**
     * 计算批次金额（用于动态挑战期高价值延长判断）。
     *
     * @param batchId 批次 ID
     * @return 批次金额；batch 不存在返回 0
     */
    private BigDecimal computeBatchValue(long batchId) {
        RollupBatch batch = batchStore.get(batchId);
        if (batch == null || batch.getTransactions() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (L2Transaction tx : batch.getTransactions()) {
            if (tx != null && tx.getAmount() != null) {
                total = total.add(new BigDecimal(tx.getAmount()));
            }
        }
        return total;
    }

    /**
     * 挑战成功时对提交者执行罚没（接入 PoS SlashingService）。
     *
     * @param batchId      批次 ID
     * @param submitter    提交者地址
     * @param slashAmount  罚没金额
     * @return 实际罚没金额；失败返回 0
     */
    public BigDecimal slashSubmitter(long batchId, String submitter, BigDecimal slashAmount) {
        if (submitter == null || slashAmount == null || slashAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal actual = BigDecimal.ZERO;
        if (slashingService != null) {
            actual = slashingService.slash(submitter, slashAmount, "FRAUD_PROVEN");
        } else {
            actual = slashAmount;
            logger.warn("SlashingService not available, simulated slash {} from {} for batch {}",
                    slashAmount, submitter, batchId);
        }
        logger.info("Slashed submitter {} for fraudulent batch {} amount {} (actual={})",
                submitter, batchId, slashAmount, actual);
        return actual;
    }

    /**
     * 奖励挑战者：返还 bond + 罚没金额 × rewardRate。
     *
     * @param challengerId 挑战者地址
     * @param reward       奖励金额
     */
    public void rewardChallenger(String challengerId, BigDecimal reward) {
        if (challengerId == null || reward == null || reward.signum() <= 0) {
            return;
        }
        ChallengeBond bond = challengeBonds.get(challengerId);
        if (bond != null && bond.getStatus() == ChallengeBond.Status.STAKED) {
            bond.setStatus(ChallengeBond.Status.RELEASED);
        }
        logger.info("Challenger {} rewarded {}", challengerId, reward);
    }

    /**
     * 质押挑战 bond。
     *
     * @param challengerId 挑战者地址
     * @param amount       质押金额
     * @return 质押成功返回 true
     */
    public boolean stakeChallengeBond(String challengerId, BigDecimal amount) {
        if (challengerId == null || amount == null || amount.signum() <= 0) {
            return false;
        }
        ChallengeBond existing = challengeBonds.get(challengerId);
        if (existing != null && existing.getStatus() == ChallengeBond.Status.STAKED) {
            logger.warn("Challenger {} already has a staked bond", challengerId);
            return false;
        }
        challengeBonds.put(challengerId, new ChallengeBond(challengerId, amount));
        logger.info("Challenge bond staked by {} amount {}", challengerId, amount);
        return true;
    }

    /**
     * 释放挑战 bond（挑战成功后调用）。
     */
    public boolean releaseChallengeBond(String challengerId) {
        ChallengeBond bond = challengeBonds.get(challengerId);
        if (bond == null) {
            return false;
        }
        bond.setStatus(ChallengeBond.Status.RELEASED);
        logger.info("Challenge bond released for {}", challengerId);
        return true;
    }

    /**
     * 标记批次为已挑战回滚。
     */
    public void markChallenged(RollupBatch batch) {
        if (batch == null) {
            return;
        }
        batch.setStatus(RollupBatchStatus.CHALLENGED);
        if (batch.getTransactions() != null) {
            for (L2Transaction tx : batch.getTransactions()) {
                tx.setStatus(L2TransactionStatus.REVERTED);
            }
        }
        logger.info("Batch {} marked as CHALLENGED", batch.getBatchId());
    }

    /**
     * 挑战期结束 → 标记 VERIFIED → 触发所有提款 finalizeWithdraw。
     *
     * @param batchId 批次 ID
     * @return finalize 成功返回 true；窗口未结束或批次不存在返回 false
     */
    public boolean finalizeBatch(long batchId) {
        if (!isChallengeWindowOver(batchId)) {
            logger.warn("Cannot finalize batch {}: challenge window not over", batchId);
            return false;
        }
        RollupBatch batch = batchStore.get(batchId);
        if (batch == null) {
            logger.warn("Cannot finalize batch {}: not found", batchId);
            return false;
        }
        if (batch.getStatus() == RollupBatchStatus.CHALLENGED) {
            logger.info("Batch {} already CHALLENGED, skip finalize", batchId);
            return false;
        }
        batch.setStatus(RollupBatchStatus.VERIFIED);
        if (batch.getTransactions() != null) {
            for (L2Transaction tx : batch.getTransactions()) {
                if (tx.getStatus() == L2TransactionStatus.INCLUDED
                        || tx.getStatus() == L2TransactionStatus.PENDING) {
                    tx.setStatus(L2TransactionStatus.CONFIRMED);
                }
            }
        }
        logger.info("Batch {} finalized as VERIFIED", batchId);
        // 触发所有提款 finalizeWithdraw
        if (bridgeContract != null) {
            bridgeContract.markBatchVerified(batchId);
            bridgeContract.finalizeWithdrawsForBatch(batchId);
        }
        return true;
    }

    public Duration getChallengeWindow() {
        return challengeWindow;
    }

    public BigDecimal getRewardRate() {
        return rewardRate;
    }

    public ChallengeBond getChallengeBond(String challengerId) {
        return challengeBonds.get(challengerId);
    }

    public RollupBatch getBatch(long batchId) {
        return batchStore.get(batchId);
    }

    public String getSubmitter(long batchId) {
        return submitterStore.get(batchId);
    }
}
