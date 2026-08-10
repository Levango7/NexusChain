package org.nexus.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InMemoryProposalRepository} 单元测试。
 */
class InMemoryProposalRepositoryTest {

    private InMemoryProposalRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryProposalRepository();
    }

    private GovernanceProposal proposal(String id, ProposalStatus status) {
        GovernanceProposal p = new GovernanceProposal();
        p.setProposalId(id);
        p.setStatus(status);
        return p;
    }

    @Test
    void saveAndFindById() {
        repo.save(proposal("p1", ProposalStatus.VOTING));
        Optional<GovernanceProposal> found = repo.findById("p1");
        assertTrue(found.isPresent());
        assertEquals("p1", found.get().getProposalId());
    }

    @Test
    void findByIdNullReturnsEmpty() {
        assertFalse(repo.findById(null).isPresent());
    }

    @Test
    void findByIdUnknownReturnsEmpty() {
        assertFalse(repo.findById("nope").isPresent());
    }

    @Test
    void saveNullIsNoOp() {
        repo.save(null);
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void saveNullIdIsNoOp() {
        GovernanceProposal p = new GovernanceProposal();
        repo.save(p);
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void findAllReturnsAll() {
        repo.save(proposal("p1", ProposalStatus.VOTING));
        repo.save(proposal("p2", ProposalStatus.PASSED));
        List<GovernanceProposal> all = repo.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void findAllEmpty() {
        assertTrue(repo.findAll().isEmpty());
    }

    @Test
    void findByStatus() {
        repo.save(proposal("p1", ProposalStatus.VOTING));
        repo.save(proposal("p2", ProposalStatus.PASSED));
        repo.save(proposal("p3", ProposalStatus.VOTING));
        List<GovernanceProposal> voting = repo.findByStatus(ProposalStatus.VOTING);
        assertEquals(2, voting.size());
        List<GovernanceProposal> passed = repo.findByStatus(ProposalStatus.PASSED);
        assertEquals(1, passed.size());
    }

    @Test
    void findByStatusNoneMatch() {
        repo.save(proposal("p1", ProposalStatus.VOTING));
        assertTrue(repo.findByStatus(ProposalStatus.EXECUTED).isEmpty());
    }

    @Test
    void deleteExistingReturnsTrue() {
        repo.save(proposal("p1", ProposalStatus.VOTING));
        assertTrue(repo.delete("p1"));
        assertFalse(repo.findById("p1").isPresent());
    }

    @Test
    void deleteUnknownReturnsFalse() {
        assertFalse(repo.delete("nope"));
    }

    @Test
    void deleteNullReturnsFalse() {
        assertFalse(repo.delete(null));
    }

    @Test
    void saveOverwrites() {
        repo.save(proposal("p1", ProposalStatus.VOTING));
        repo.save(proposal("p1", ProposalStatus.PASSED));
        assertEquals(1, repo.findAll().size());
        assertEquals(ProposalStatus.PASSED, repo.findById("p1").get().getStatus());
    }
}