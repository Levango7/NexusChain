package org.nexus.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.consensus.pos.StakingService;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link GovernanceVotingService} 单元测试。
 */
class GovernanceVotingServiceTest {

    private GovernanceVotingService service;
    private StakingService staking;
    private VoteRecordRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        service = new GovernanceVotingService();
        staking = mock(StakingService.class);
        repo = mock(VoteRecordRepository.class);
        inject(service, "stakingService", staking);
        inject(service, "voteRepository", repo);
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void voteWithStakeRecordsVote() {
        when(staking.getStake("v1")).thenReturn(BigDecimal.TEN);
        service.vote("p1", "v1", VoteOption.YES, Instant.now());
        verify(repo).recordVote(eq("p1"), eq("v1"), any());
    }

    @Test
    void voteWithNoStakeRejected() {
        when(staking.getStake("v1")).thenReturn(BigDecimal.ZERO);
        service.vote("p1", "v1", VoteOption.YES, Instant.now());
        verify(repo, never()).recordVote(any(), any(), any());
    }

    @Test
    void voteWithNegativeStakeRejected() {
        when(staking.getStake("v1")).thenReturn(new BigDecimal("-1"));
        service.vote("p1", "v1", VoteOption.YES, Instant.now());
        verify(repo, never()).recordVote(any(), any(), any());
    }

    @Test
    void voteNullProposalIdIsNoOp() {
        service.vote(null, "v1", VoteOption.YES, Instant.now());
        verify(repo, never()).recordVote(any(), any(), any());
    }

    @Test
    void voteNullVoterIsNoOp() {
        service.vote("p1", null, VoteOption.YES, Instant.now());
        verify(repo, never()).recordVote(any(), any(), any());
    }

    @Test
    void voteNullOptionIsNoOp() {
        service.vote("p1", "v1", null, Instant.now());
        verify(repo, never()).recordVote(any(), any(), any());
    }

    @Test
    void hasVotedDelegatesToRepository() {
        when(repo.hasVoted("p1", "v1")).thenReturn(true);
        assertTrue(service.hasVoted("p1", "v1"));
        verify(repo).hasVoted("p1", "v1");
    }

    @Test
    void clearVotesDelegatesToRepository() {
        service.clearVotes("p1");
        verify(repo).clearVotes("p1");
    }

    @Test
    void tallyAggregatesByOption() {
        java.util.Map<String, GovernanceVotingService.VoteRecord> votes = new java.util.HashMap<>();
        votes.put("v1", new GovernanceVotingService.VoteRecord(
                VoteOption.YES, BigDecimal.valueOf(10), Instant.now()));
        votes.put("v2", new GovernanceVotingService.VoteRecord(
                VoteOption.NO, BigDecimal.valueOf(5), Instant.now()));
        votes.put("v3", new GovernanceVotingService.VoteRecord(
                VoteOption.ABSTAIN, BigDecimal.valueOf(3), Instant.now()));
        when(repo.getVotes("p1")).thenReturn(votes);

        GovernanceVotingService.VoteTally t = service.tally("p1");
        assertEquals(BigDecimal.valueOf(10), t.getYes());
        assertEquals(BigDecimal.valueOf(5), t.getNo());
        assertEquals(BigDecimal.valueOf(3), t.getAbstain());
    }

    @Test
    void tallySkipsUnrevealedCommitRecords() {
        java.util.Map<String, GovernanceVotingService.VoteRecord> votes = new java.util.HashMap<>();
        // commit 阶段记录（未揭示）
        votes.put("v1", GovernanceVotingService.newCommitRecord("hash", BigDecimal.TEN, Instant.now()));
        when(repo.getVotes("p1")).thenReturn(votes);

        GovernanceVotingService.VoteTally t = service.tally("p1");
        assertEquals(BigDecimal.ZERO, t.getYes());
        assertEquals(BigDecimal.ZERO, t.getNo());
        assertEquals(BigDecimal.ZERO, t.getAbstain());
    }

    @Test
    void newCommitRecordHasHashNoRevealedVote() {
        GovernanceVotingService.VoteRecord r =
                GovernanceVotingService.newCommitRecord("hash", BigDecimal.TEN, Instant.now());
        assertEquals("hash", r.getCommitHash());
        assertFalse(r.isRevealed());
        assertNull(r.getRevealedVote());
        assertNull(r.effectiveVote());
    }

    @Test
    void newRevealedRecordPreservesWeightAndRevealsVote() {
        GovernanceVotingService.VoteRecord commit =
                GovernanceVotingService.newCommitRecord("hash", BigDecimal.TEN, Instant.now());
        GovernanceVotingService.VoteRecord revealed =
                GovernanceVotingService.newRevealedRecord(commit, VoteOption.YES, "salt");
        assertTrue(revealed.isRevealed());
        assertEquals(VoteOption.YES, revealed.getRevealedVote());
        assertEquals("salt", revealed.getSalt());
        assertEquals(BigDecimal.TEN, revealed.getWeight());
        assertEquals(VoteOption.YES, revealed.effectiveVote());
    }

    @Test
    void newRevealedRecordNullCommitReturnsZeroWeight() {
        GovernanceVotingService.VoteRecord r =
                GovernanceVotingService.newRevealedRecord(null, VoteOption.NO, "salt");
        assertEquals(VoteOption.NO, r.getRevealedVote());
        assertEquals(BigDecimal.ZERO, r.getWeight());
    }

    @Test
    void directVoteRecordIsRevealedWithOption() {
        GovernanceVotingService.VoteRecord r = new GovernanceVotingService.VoteRecord(
                VoteOption.YES, BigDecimal.TEN, Instant.now());
        assertTrue(r.isRevealed());
        assertEquals(VoteOption.YES, r.getOption());
        assertEquals(VoteOption.YES, r.effectiveVote());
        assertNull(r.getCommitHash());
        assertNull(r.getSalt());
    }
}