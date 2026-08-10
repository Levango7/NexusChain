package org.nexus.governance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InMemoryExecutionStateRepository} 单元测试。
 */
class InMemoryExecutionStateRepositoryTest {

    private InMemoryExecutionStateRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryExecutionStateRepository();
    }

    @Test
    void saveAndGet() {
        ExecutionState s = new ExecutionState("tx-1", Instant.now());
        repo.save("p1", s);
        assertSame(s, repo.get("p1"));
    }

    @Test
    void getNullReturnsNull() {
        assertNull(repo.get(null));
    }

    @Test
    void getUnknownReturnsNull() {
        assertNull(repo.get("unknown"));
    }

    @Test
    void saveNullProposalIdIsNoOp() {
        ExecutionState s = new ExecutionState("tx", Instant.now());
        repo.save(null, s);
        // 不应抛异常
        assertNull(repo.get(null));
    }

    @Test
    void saveNullStateIsNoOp() {
        repo.save("p1", null);
        assertNull(repo.get("p1"));
    }

    @Test
    void removeExistingReturnsTrue() {
        repo.save("p1", new ExecutionState("tx", Instant.now()));
        assertTrue(repo.remove("p1"));
        assertNull(repo.get("p1"));
    }

    @Test
    void removeUnknownReturnsFalse() {
        assertFalse(repo.remove("unknown"));
    }

    @Test
    void removeNullReturnsFalse() {
        assertFalse(repo.remove(null));
    }

    @Test
    void saveOverwrites() {
        ExecutionState s1 = new ExecutionState("tx-1", Instant.now());
        ExecutionState s2 = new ExecutionState("tx-2", Instant.now());
        repo.save("p1", s1);
        repo.save("p1", s2);
        assertSame(s2, repo.get("p1"));
    }
}