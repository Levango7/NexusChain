package org.nexus.governance;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存投票记录仓储默认实现。
 *
 * <p>基于 {@link ConcurrentHashMap}，保持与重构前 {@code GovernanceVotingService.votes}
 * 相同的并发语义与向后兼容性。</p>
 *
 * @since 1.3
 */
@Component
public class InMemoryVoteRecordRepository implements VoteRecordRepository {

    /** proposalId -> (voter -> vote record) */
    private final ConcurrentHashMap<String, Map<String, GovernanceVotingService.VoteRecord>> store =
            new ConcurrentHashMap<>();

    @Override
    public void recordVote(String proposalId, String voter, GovernanceVotingService.VoteRecord record) {
        if (proposalId == null || voter == null || record == null) {
            return;
        }
        store.computeIfAbsent(proposalId, k -> new ConcurrentHashMap<>()).put(voter, record);
    }

    @Override
    public Map<String, GovernanceVotingService.VoteRecord> getVotes(String proposalId) {
        if (proposalId == null) {
            return Collections.emptyMap();
        }
        Map<String, GovernanceVotingService.VoteRecord> map = store.get(proposalId);
        if (map == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(map);
    }

    @Override
    public boolean hasVoted(String proposalId, String voter) {
        if (proposalId == null || voter == null) {
            return false;
        }
        Map<String, GovernanceVotingService.VoteRecord> map = store.get(proposalId);
        return map != null && map.containsKey(voter);
    }

    @Override
    public void clearVotes(String proposalId) {
        if (proposalId != null) {
            store.remove(proposalId);
        }
    }
}