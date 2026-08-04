package org.nexus.oracle.governance;

/**
 * 链上治理服务。
 *
 * <p>提供提案创建、投票、执行与状态查询能力，覆盖
 * 参数升级、软件升级、国库支出三类提案。
 */
public interface GovernanceService {

    /**
     * 创建提案。
     *
     * @param proposal 提案内容（不含分配的 proposalId）
     * @return 已持久化的提案（含 proposalId 与初始状态 PENDING）
     */
    Proposal createProposal(Proposal proposal);

    /**
     * 对提案投票。
     *
     * @param proposalId 提案 ID
     * @param vote       投票内容
     * @return 是否成功计入（提案不存在 / 不在投票期 / 已投过票时返回 false）
     */
    boolean vote(String proposalId, Vote vote);

    /**
     * 执行已通过且过执行延迟的提案。
     *
     * @param proposalId 提案 ID
     * @return 是否成功执行
     */
    boolean executeProposal(String proposalId);

    /**
     * 查询提案当前状态。
     *
     * @param proposalId 提案 ID
     * @return 提案状态；提案不存在时返回 {@code null}
     */
    ProposalState getProposalState(String proposalId);

    /**
     * 查询提案完整信息。
     *
     * @param proposalId 提案 ID
     * @return 提案对象；不存在时返回 {@code null}
     */
    Proposal getProposal(String proposalId);
}