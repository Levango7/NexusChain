package org.nexus.governance;

/**
 * 链上治理接口。
 *
 * <p>定义提案提交、投票、计票与执行能力。</p>
 *
 * @since 1.2
 */
public interface OnChainGovernance {

    /**
     * 提交新提案。
     *
     * @param proposal 提案内容
     * @return 提案 ID
     */
    String submitProposal(GovernanceProposal proposal);

    /**
     * 对指定提案投票。
     *
     * @param proposalId 提案 ID
     * @param vote       投票选项
     */
    void vote(String proposalId, VoteOption vote);

    /**
     * 对指定提案计票。
     *
     * @param proposalId 提案 ID
     * @return 通过返回 true，否则 false
     */
    boolean tallyVotes(String proposalId);

    /**
     * 若提案已通过则执行生效。
     *
     * @param proposalId 提案 ID
     * @return 执行成功返回 true
     */
    boolean executeIfPassed(String proposalId);
}