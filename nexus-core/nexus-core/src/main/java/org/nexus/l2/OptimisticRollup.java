package org.nexus.l2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optimistic Rollup 实现。
 *
 * <p>基于欺诈证明的乐观 Rollup：假设提交者诚实，在挑战窗口内可被挑战回滚。
 * 提交批次 → 计算状态根 → 提交到 L1 → 开启挑战窗口；
 * 窗口结束无挑战则 VERIFIED；窗口内可提交欺诈证明挑战回滚并罚没提交者。</p>
 *
 * @since 1.2
 */
@Component
public class OptimisticRollup implements RollupManager {

    private static final Logger logger = LoggerFactory.getLogger(OptimisticRollup.class);

    /** 默认罚没金额 */
    private static final BigDecimal DEFAULT_SLASH_AMOUNT = new BigDecimal("1000");

    @Autowired
    private StateRootManager stateRootManager;

    @Autowired
    private FraudProofVerifier fraudProofVerifier;

    @Autowired
    private L2BridgeContract bridge;

    private final AtomicLong nextBatchId = new AtomicLong(1L);

    @Override
    public long submitBatch(List<L2Transaction> transactions) {
        long batchId = nextBatchId.getAndIncrement();
        String submitter = "sequencer";
        RollupBatch batch = buildBatch(batchId, transactions, submitter);
        String stateRoot = stateRootManager.applyBatch(batch);
        batch.setStateRoot(stateRoot);
        bridge.submitStateRoot(batchId, stateRoot);
        fraudProofVerifier.onSubmit(batch, submitter);
        logger.info("OptimisticRollup submitBatch {} with {} txs, root={}",
                batchId, transactions == null ? 0 : transactions.size(), stateRoot);
        return batchId;
    }

    /**
     * 提交批次（带显式 batchId、stateRoot、submitter）。
     *
     * @param batchId      批次 ID
     * @param transactions 交易列表
     * @param stateRoot    状态根
     * @param submitter    提交者地址
     * @return 提交成功返回 batchId
     */
    public long submitBatch(long batchId, List<L2Transaction> transactions,
                            String stateRoot, String submitter) {
        RollupBatch batch = buildBatch(batchId, transactions, submitter);
        batch.setStateRoot(stateRoot);
        bridge.submitStateRoot(batchId, stateRoot);
        fraudProofVerifier.onSubmit(batch, submitter);
        logger.info("OptimisticRollup submitBatch {} from submitter {} with {} txs, root={}",
                batchId, submitter, transactions == null ? 0 : transactions.size(), stateRoot);
        return batchId;
    }

    @Override
    public boolean verifyBatch(long batchId) {
        if (!fraudProofVerifier.isChallengeWindowOver(batchId)) {
            logger.debug("Batch {} challenge window not over yet", batchId);
            return false;
        }
        RollupBatch batch = fraudProofVerifier.getBatch(batchId);
        if (batch == null) {
            logger.warn("verifyBatch: batch {} not found", batchId);
            return false;
        }
        if (batch.getStatus() == RollupBatchStatus.CHALLENGED) {
            logger.info("Batch {} was CHALLENGED, cannot verify", batchId);
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
        logger.info("Batch {} VERIFIED", batchId);
        return true;
    }

    @Override
    public boolean challengeBatch(long batchId, Object proof) {
        if (!(proof instanceof FraudProof)) {
            logger.warn("challengeBatch: invalid proof type for batch {}", batchId);
            return false;
        }
        FraudProof fp = (FraudProof) proof;
        if (fp.getBatchId() != batchId) {
            logger.warn("challengeBatch: batchId mismatch ({} vs {})", fp.getBatchId(), batchId);
            return false;
        }
        // 检查挑战者 bond
        String challenger = fp.getChallenger();
        ChallengeBond bond = fraudProofVerifier.getChallengeBond(challenger);
        if (bond == null || bond.getStatus() != ChallengeBond.Status.STAKED) {
            logger.warn("challengeBatch: challenger {} has no staked bond", challenger);
            return false;
        }
        // 自 1.3 起使用 first-valid-wins 多挑战者冲突解决
        org.nexus.l2.challenge.ChallengeConflictResult result = fraudProofVerifier.submitChallenge(fp);
        switch (result) {
            case FIRST_VALID:
                logger.info("challengeBatch: batch {} CHALLENGED (FIRST_VALID) by challenger {}",
                        batchId, challenger);
                return true;
            case DUPLICATE_AFTER_VALID:
                logger.info("challengeBatch: batch {} already challenged, challenger {} bond refunded",
                        batchId, challenger);
                return true;
            case INVALID_PROOF:
                logger.info("challengeBatch: fraud proof rejected for batch {}, challenger {} bond slashed",
                        batchId, challenger);
                return false;
            case WINDOW_CLOSED:
                logger.info("challengeBatch: window closed for batch {}", batchId);
                return false;
            default:
                logger.warn("challengeBatch: result {} for batch {}", result, batchId);
                return false;
        }
    }

    private RollupBatch buildBatch(long batchId, List<L2Transaction> transactions, String submitter) {
        RollupBatch batch = new RollupBatch();
        batch.setBatchId(batchId);
        List<L2Transaction> txs = transactions == null ? new ArrayList<>() : new ArrayList<>(transactions);
        for (L2Transaction tx : txs) {
            tx.setBatchId(batchId);
            if (tx.getStatus() == null) {
                tx.setStatus(L2TransactionStatus.INCLUDED);
            }
        }
        batch.setTransactions(txs);
        batch.setSubmitter(submitter);
        batch.setStatus(RollupBatchStatus.SUBMITTED);
        return batch;
    }

    public long getNextBatchId() {
        return nextBatchId.get();
    }
}
