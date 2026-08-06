package org.nexus.l2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 欺诈证明验证器。
 *
 * <p>用于 Optimistic Rollup 的挑战窗口管理与欺诈证明校验。
 * 批次提交后开启挑战窗口，窗口内任何节点可提交欺诈证明，
 * 证明状态转换非法则回滚批次并惩罚提交者。</p>
 *
 * @since 1.2
 */
@Component
public class FraudProofVerifier {

    private static final Logger logger = LoggerFactory.getLogger(FraudProofVerifier.class);

    /** 默认挑战窗口：7 天 */
    private static final Duration DEFAULT_CHALLENGE_WINDOW = Duration.ofDays(7);

    @Autowired
    private StateRootManager stateRootManager;

    private final Duration challengeWindow;

    /** 批次 ID -> 提交时间 */
    private final Map<Long, Instant> batchSubmitTime = new ConcurrentHashMap<>();

    public FraudProofVerifier() {
        this(DEFAULT_CHALLENGE_WINDOW);
    }

    public FraudProofVerifier(Duration challengeWindow) {
        this.challengeWindow = challengeWindow;
    }

    /**
     * 批次提交时调用，开启挑战窗口。
     *
     * @param batchId 批次 ID
     */
    public void onSubmit(long batchId) {
        batchSubmitTime.put(batchId, Instant.now());
        logger.info("Challenge window opened for batch {} (duration={})", batchId, challengeWindow);
    }

    /**
     * 验证欺诈证明。
     *
     * <p>若声明的状态转换与本地重算结果不一致，则欺诈证明成立，
     * 批次确为欺诈。</p>
     *
     * @param batchId        批次 ID
     * @param claimedPrevRoot 声明的前一状态根
     * @param claimedNewRoot  声明的新状态根
     * @param batch          批次交易
     * @return 欺诈证明成立返回 true；批次为合法返回 false
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
     * 判断指定批次的挑战窗口是否已结束。
     *
     * @param batchId 批次 ID
     * @return 窗口已结束返回 true；未提交或仍在窗口内返回 false
     */
    public boolean isChallengeWindowOver(long batchId) {
        Instant submit = batchSubmitTime.get(batchId);
        if (submit == null) {
            return false;
        }
        return Instant.now().isAfter(submit.plus(challengeWindow));
    }

    /**
     * 挑战成功时对提交者执行惩罚（罚没其质押保证金）。
     *
     * @param batchId      批次 ID
     * @param submitter    提交者地址
     * @param slashAmount  罚没金额
     * @return 惩罚执行成功返回 true
     */
    public boolean slashSubmitter(long batchId, String submitter, BigDecimal slashAmount) {
        logger.info("Slashed submitter {} for fraudulent batch {} amount {}", submitter, batchId, slashAmount);
        // TODO: 调用 PoS SlashingService 或 L1 桥合约执行实际罚没
        return true;
    }

    /**
     * 标记批次为已挑战回滚。
     *
     * @param batch 批次
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

    public Duration getChallengeWindow() {
        return challengeWindow;
    }
}