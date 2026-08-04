package org.nexus.oracle.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link GovernanceService} 默认骨架实现。
 *
 * <p>当前为占位实现，所有写操作返回失败 / 空结果，读操作返回 null。
 * 后续接入提案存储 + 链上治理合约后填充业务逻辑。
 */
@Slf4j
@Service
public class DefaultGovernanceService implements GovernanceService {

    @Override
    public Proposal createProposal(Proposal proposal) {
        // TODO: 校验提案参数 → 分配 proposalId → 持久化 → 设置 PENDING + votingStart
        log.debug("createProposal skeleton invoked: title={}, type={}",
                proposal.getTitle(), proposal.getType());
        return proposal;
    }

    @Override
    public boolean vote(String proposalId, Vote vote) {
        // TODO: 校验提案状态为 ACTIVE + 投票者未重复投票 → 累计票数 → 持久化
        log.debug("vote skeleton invoked: proposalId={}, voter={}, option={}",
                proposalId, vote.getVoter(), vote.getOption());
        return false;
    }

    @Override
    public boolean executeProposal(String proposalId) {
        // TODO: 校验状态为 PASSED + 已过 executionDelay → 按 type 分发执行 → 标记 EXECUTED
        log.debug("executeProposal skeleton invoked: proposalId={}", proposalId);
        return false;
    }

    @Override
    public ProposalState getProposalState(String proposalId) {
        // TODO: 查询提案并返回当前状态（含按时间自动推进 ACTIVE → PASSED/REJECTED）
        log.debug("getProposalState skeleton invoked: proposalId={}", proposalId);
        return null;
    }

    @Override
    public Proposal getProposal(String proposalId) {
        // TODO: 查询提案完整信息
        log.debug("getProposal skeleton invoked: proposalId={}", proposalId);
        return null;
    }
}