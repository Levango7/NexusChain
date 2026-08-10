package org.nexus.governance;

import java.util.List;
import java.util.Optional;

/**
 * 治理提案持久化仓储接口。
 *
 * <p>抽象 {@link GovernanceService} 中提案的存储与查询职责，
 * 默认实现 {@link InMemoryProposalRepository} 保持原有 {@code ConcurrentHashMap} 行为；
 * 后续可提供关系库实现以支持持久化。</p>
 *
 * @since 1.3
 */
public interface GovernanceProposalRepository {

    /**
     * 保存或更新提案。
     *
     * @param proposal 提案
     */
    void save(GovernanceProposal proposal);

    /**
     * 按主键查询提案。
     *
     * @param proposalId 提案 ID
     * @return 提案；不存在返回 {@link Optional#empty()}
     */
    Optional<GovernanceProposal> findById(String proposalId);

    /**
     * 列出全部提案。
     *
     * @return 提案列表
     */
    List<GovernanceProposal> findAll();

    /**
     * 列出处于指定状态的提案。
     *
     * @param status 状态
     * @return 提案列表
     */
    List<GovernanceProposal> findByStatus(ProposalStatus status);

    /**
     * 删除提案。
     *
     * @param proposalId 提案 ID
     * @return 删除成功返回 true
     */
    boolean delete(String proposalId);
}