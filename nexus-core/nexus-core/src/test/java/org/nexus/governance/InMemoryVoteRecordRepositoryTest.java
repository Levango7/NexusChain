package org.nexus.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.governance.GovernanceVotingService.VoteRecord;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InMemoryVoteRecordRepository} 单元测试。
 */
class InMemoryVoteRecordRepositoryTest {

    private InMemoryVoteRecordRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryVoteRecordRepository();
    }

    private VoteRecord record(VoteOption opt) {
        return new VoteRecord(opt, BigDecimal.TEN, Instant.now());
    }

    @Test
    void recordVoteAndGetVotes() {
        repo.recordVote("p1", "v1", record(VoteOption.YES));
        Map<String, VoteRecord> votes = repo.getVotes("p1");
        assertEquals(1, votes.size());
        assertNotNull(votes.get("v1"));
    }

    @Test
    void getVotesNullReturnsEmpty() {
        assertTrue(repo.getVotes(null).isEmpty());
    }

    @Test
    void getVotesUnknownReturnsEmpty() {
        assertTrue(repo.getVotes("unknown").isEmpty());
    }

    @Test
    void recordVoteNullProposalIdIsNoOp() {
        repo.recordVote(null, "v1", record(VoteOption.YES));
        assertTrue(repo.getVotes(null).isEmpty());
    }

    @Test
    void recordVoteNullVoterIsNoOp() {
        repo.recordVote("p1", null, record(VoteOption.YES));
        assertTrue(repo.getVotes("p1").isEmpty());
    }

    @Test
    void recordVoteNullRecordIsNoOp() {
        repo.recordVote("p1", "v1", null);
        assertTrue(repo.getVotes("p1").isEmpty());
    }

    @Test
    void hasVoted() {
        repo.recordVote("p1", "v1", record(VoteOption.YES));
        assertTrue(repo.hasVoted("p1", "v1"));
        assertFalse(repo.hasVoted("p1", "v2"));
    }

    @Test
    void hasVotedNullReturnsFalse() {
        assertFalse(repo.hasVoted(null, "v1"));
        assertFalse(repo.hasVoted("p1", null));
    }

    @Test
    void clearVotesRemovesAll() {
        repo.recordVote("p1", "v1", record(VoteOption.YES));
        repo.recordVote("p1", "v2", record(VoteOption.NO));
        repo.clearVotes("p1");
        assertTrue(repo.getVotes("p1").isEmpty());
        assertFalse(repo.hasVoted("p1", "v1"));
    }

    @Test
    void clearVotesNullIsNoOp() {
        repo.clearVotes(null); // 不应抛异常
    }

    @Test
    void sameVoterOverwrites() {
        repo.recordVote("p1", "v1", record(VoteOption.YES));
        repo.recordVote("p1", "v1", record(VoteOption.NO));
        Map<String, VoteRecord> votes = repo.getVotes("p1");
        assertEquals(1, votes.size());
        assertEquals(VoteOption.NO, votes.get("v1").getOption());
    }

    @Test
    void votesMapIsUnmodifiable() {
        repo.recordVote("p1", "v1", record(VoteOption.YES));
        assertThrows(UnsupportedOperationException.class, () ->
                repo.getVotes("p1").put("v2", record(VoteOption.NO)));
    }
}