package org.nexus.l2.challenge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多挑战者冲突解决器（first-valid-wins 策略）。
 *
 * <p>当多个挑战者对同一批次提交欺诈证明时，采用 <b>first-valid-wins</b> 规则：</p>
 * <ol>
 *   <li>首个通过验证的欺诈证明<b>生效</b>：挑战者获得奖励，提交者被罚没，批次回滚</li>
 *   <li>后续挑战者的欺诈证明若也有效，<b>bond 退还</b>（不罚没），不重复触发 slashing</li>
 *   <li>证明无效的挑战者 bond <b>罚没</b>（无论是否已有有效证明）</li>
 *   <li>挑战窗口已关闭后所有挑战拒绝</li>
 * </ol>
 *
 * <p>设计动机：鼓励多挑战者竞争发现欺诈，避免"先到先得奖励"导致观望；
 * 后续有效挑战者虽无奖励但不损失 bond，提高挑战积极性。</p>
 *
 * <p>本组件仅负责冲突解决状态管理，实际 slashing/reward 由
 * {@code FraudProofVerifier} 调用方根据 {@link ChallengeConflictResult} 执行。</p>
 *
 * @since 1.3
 */
@Component
public class ChallengeConflictResolver {

    private static final Logger logger = LoggerFactory.getLogger(ChallengeConflictResolver.class);

    /** 批次 ID -> 首个有效挑战者地址 */
    private final Map<Long, String> firstValidChallenger = new ConcurrentHashMap<>();

    /** 批次 ID -> 首个有效欺诈证明 */
    private final Map<Long, Object> firstValidProof = new ConcurrentHashMap<>();

    /** 批次 ID -> 所有挑战者地址（按提交顺序） */
    private final Map<Long, List<String>> allChallengers = new ConcurrentHashMap<>();

    /** 批次 ID -> 已退还 bond 的挑战者集合（first-valid-wins 后续竞争者） */
    private final Map<Long, List<String>> refundedChallengers = new ConcurrentHashMap<>();

    /** 批次 ID -> 已罚没 bond 的挑战者集合（证明无效者） */
    private final Map<Long, List<String>> slashedChallengers = new ConcurrentHashMap<>();

    /**
     * 解决一次挑战请求（first-valid-wins）。
     *
     * <p>本方法<b>仅</b>根据"批次是否已有有效证明"与"本次证明是否有效"决定处置结果，
     * 不执行 slashing/reward，由调用方根据返回值执行。</p>
     *
     * @param batchId       批次 ID
     * @param challenger    挑战者地址
     * @param proofValid    本次欺诈证明是否通过验证
     * @param windowClosed  挑战窗口是否已关闭
     * @return 冲突解决结果
     */
    public synchronized ChallengeConflictResult resolveChallenge(
            long batchId, String challenger, boolean proofValid, boolean windowClosed) {
        if (challenger == null) {
            return ChallengeConflictResult.NO_BOND;
        }
        // 记入挑战者列表
        allChallengers.computeIfAbsent(batchId, k -> Collections.synchronizedList(new ArrayList<>())).add(challenger);

        if (windowClosed) {
            logger.info("Challenge from {} for batch {} rejected: window closed", challenger, batchId);
            return ChallengeConflictResult.WINDOW_CLOSED;
        }

        boolean hasValid = firstValidChallenger.containsKey(batchId);

        if (!proofValid) {
            // 证明无效：罚没 bond（无论是否已有有效证明）
            slashedChallengers.computeIfAbsent(batchId, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(challenger);
            logger.info("Challenge from {} for batch {} INVALID_PROOF (hasValidPrior={})",
                    challenger, batchId, hasValid);
            return ChallengeConflictResult.INVALID_PROOF;
        }

        // 证明有效
        if (!hasValid) {
            // 首个有效证明：生效
            firstValidChallenger.put(batchId, challenger);
            logger.info("Challenge from {} for batch {} FIRST_VALID (first valid proof)", challenger, batchId);
            return ChallengeConflictResult.FIRST_VALID;
        }

        // 已有有效证明：本次 bond 退还（first-valid-wins 后续竞争者不罚没）
        refundedChallengers.computeIfAbsent(batchId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(challenger);
        logger.info("Challenge from {} for batch {} DUPLICATE_AFTER_VALID (bond refunded, firstValid={})",
                challenger, batchId, firstValidChallenger.get(batchId));
        return ChallengeConflictResult.DUPLICATE_AFTER_VALID;
    }

    /**
     * 记入首个有效证明（在 FIRST_VALID 结果后调用，记录证明实体）。
     *
     * @param batchId 批次 ID
     * @param proof   欺诈证明
     */
    public void recordFirstValidProof(long batchId, Object proof) {
        firstValidProof.put(batchId, proof);
    }

    /**
     * 查询批次是否已有有效证明生效。
     *
     * @param batchId 批次 ID
     * @return 已有有效证明返回 true
     */
    public boolean hasValidProof(long batchId) {
        return firstValidChallenger.containsKey(batchId);
    }

    /**
     * 获取首个有效挑战者地址。
     *
     * @param batchId 批次 ID
     * @return 首个有效挑战者；不存在返回 null
     */
    public String getFirstValidChallenger(long batchId) {
        return firstValidChallenger.get(batchId);
    }

    /**
     * 获取首个有效欺诈证明。
     *
     * @param batchId 批次 ID
     * @return 首个有效证明；不存在返回 null
     */
    public Object getFirstValidProof(long batchId) {
        return firstValidProof.get(batchId);
    }

    /**
     * 获取批次所有挑战者（按提交顺序）。
     *
     * @param batchId 批次 ID
     * @return 挑战者列表（不可变）；无挑战返回空列表
     */
    public List<String> getAllChallengers(long batchId) {
        List<String> list = allChallengers.get(batchId);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * 获取已退还 bond 的挑战者列表。
     *
     * @param batchId 批次 ID
     * @return 已退还 bond 的挑战者列表
     */
    public List<String> getRefundedChallengers(long batchId) {
        List<String> list = refundedChallengers.get(batchId);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * 获取已罚没 bond 的挑战者列表。
     *
     * @param batchId 批次 ID
     * @return 已罚没 bond 的挑战者列表
     */
    public List<String> getSlashedChallengers(long batchId) {
        List<String> list = slashedChallengers.get(batchId);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    /**
     * 清除批次冲突状态（批次 finalize 或回滚后调用）。
     *
     * @param batchId 批次 ID
     */
    public void clear(long batchId) {
        firstValidChallenger.remove(batchId);
        firstValidProof.remove(batchId);
        allChallengers.remove(batchId);
        refundedChallengers.remove(batchId);
        slashedChallengers.remove(batchId);
    }
}