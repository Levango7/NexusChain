package org.nexus.governance;

import java.util.Map;

/**
 * 治理投票记录持久化仓储接口。
 *
 * <p>抽象 {@link GovernanceVotingService} 中投票记录的存储职责，
 * 默认实现 {@link InMemoryVoteRecordRepository} 保持原有 {@code ConcurrentHashMap} 行为。</p>
 *
 * @since 1.3
 */
public interface VoteRecordRepository {

    /**
     * 记录或覆盖一票（同一投票人重复投票以后次为准）。
     *
     * @param proposalId 提案 ID
     * @param voter      投票人
     * @param record     投票记录
     */
    void recordVote(String proposalId, String voter, GovernanceVotingService.VoteRecord record);

    /**
     * 返回指定提案下所有投票记录（只读视图）。
     *
     * @param proposalId 提案 ID
     * @return voter -> record 的只读 Map；不存在返回空 Map
     */
    Map<String, GovernanceVotingService.VoteRecord> getVotes(String proposalId);

    /**
     * 判断投票人是否已对提案投票。
     *
     * @param proposalId 提案 ID
     * @param voter      投票人
     * @return 已投票返回 true
     */
    boolean hasVoted(String proposalId, String voter);

    /**
     * 清空指定提案的投票记录。
     *
     * @param proposalId 提案 ID
     */
    void clearVotes(String proposalId);
}