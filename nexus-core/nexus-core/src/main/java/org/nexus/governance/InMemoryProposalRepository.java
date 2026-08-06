package org.nexus.governance;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存提案仓储默认实现。
 *
 * <p>基于 {@link ConcurrentHashMap}，保持与重构前 {@code GovernanceService.proposals}
 * 相同的并发语义与向后兼容性。生产环境可替换为关系库实现。</p>
 *
 * @since 1.3
 */
@Component
public class InMemoryProposalRepository implements GovernanceProposalRepository {

    private final ConcurrentHashMap<String, GovernanceProposal> store = new ConcurrentHashMap<>();

    @Override
    public void save(GovernanceProposal proposal) {
        if (proposal == null || proposal.getProposalId() == null) {
            return;
        }
        store.put(proposal.getProposalId(), proposal);
    }

    @Override
    public Optional<GovernanceProposal> findById(String proposalId) {
        if (proposalId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(proposalId));
    }

    @Override
    public List<GovernanceProposal> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<GovernanceProposal> findByStatus(ProposalStatus status) {
        List<GovernanceProposal> result = new ArrayList<>();
        for (GovernanceProposal p : store.values()) {
            if (p.getStatus() == status) {
                result.add(p);
            }
        }
        return result;
    }

    @Override
    public boolean delete(String proposalId) {
        if (proposalId == null) {
            return false;
        }
        return store.remove(proposalId) != null;
    }
}