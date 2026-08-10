package org.nexus.l2.challenge;

/**
 * 挑战冲突解决结果。
 *
 * <p>描述 {@link ChallengeConflictResolver#resolveChallenge} 对单个挑战请求的处置：</p>
 * <ul>
 *   <li>{@link #FIRST_VALID}：首个通过验证的欺诈证明生效，挑战者获得奖励，提交者被罚没</li>
 *   <li>{@link #DUPLICATE_AFTER_VALID}：已有有效证明生效，本次挑战者 bond 退还（不罚没，鼓励竞争）</li>
 *   <li>{@link #INVALID_PROOF}：欺诈证明验证失败，本次挑战者 bond 罚没</li>
 *   <li>{@link #WINDOW_CLOSED}：挑战窗口已关闭，不接受新挑战</li>
 *   <li>{@link #NO_BOND}：挑战者未质押 bond，挑战拒绝</li>
 *   <li>{@link #BATCH_NOT_FOUND}：批次不存在</li>
 * </ul>
 *
 * @since 1.3
 */
public enum ChallengeConflictResult {

    /** 首个通过验证的欺诈证明生效 */
    FIRST_VALID,

    /** 已有有效证明生效，本次挑战者 bond 退还（first-valid-wins 后续竞争者不罚没） */
    DUPLICATE_AFTER_VALID,

    /** 欺诈证明验证失败，本次挑战者 bond 罚没 */
    INVALID_PROOF,

    /** 挑战窗口已关闭，不接受新挑战 */
    WINDOW_CLOSED,

    /** 挑战者未质押 bond */
    NO_BOND,

    /** 批次不存在 */
    BATCH_NOT_FOUND;

    /**
     * 判断结果是否表示挑战被接受（生效或竞争性退还）。
     *
     * @return 挑战被接受返回 true
     */
    public boolean isAccepted() {
        return this == FIRST_VALID || this == DUPLICATE_AFTER_VALID;
    }

    /**
     * 判断结果是否表示挑战者 bond 应被退还（不罚没）。
     *
     * @return bond 应退还返回 true
     */
    public boolean isBondRefunded() {
        return this == DUPLICATE_AFTER_VALID;
    }

    /**
     * 判断结果是否表示挑战者 bond 应被罚没。
     *
     * @return bond 应罚没返回 true
     */
    public boolean isBondSlashed() {
        return this == INVALID_PROOF;
    }
}