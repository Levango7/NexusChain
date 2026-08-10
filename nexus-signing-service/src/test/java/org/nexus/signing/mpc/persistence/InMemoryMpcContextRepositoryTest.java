package org.nexus.signing.mpc.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryMpcContextRepository} 单元测试。
 */
public class InMemoryMpcContextRepositoryTest {

    private InMemoryMpcContextRepository repo;

    @BeforeEach
    public void setUp() {
        repo = new InMemoryMpcContextRepository();
    }

    private MpcProtocolContext newContext(String sessionId, int round) {
        MpcProtocolContext ctx = new MpcProtocolContext();
        ctx.setSessionId(sessionId);
        ctx.setRound(round);
        ctx.setParticipantId("p1");
        Map<String, String> state = new HashMap<>();
        state.put("key", "value");
        ctx.setState(state);
        return ctx;
    }

    @Test
    public void testSaveAndFindBySessionAndRound() {
        repo.save(newContext("s1", 1));
        Optional<MpcProtocolContext> found = repo.findBySessionAndRound("s1", 1);
        assertTrue(found.isPresent());
        assertEquals(1, found.get().getRound());
    }

    @Test
    public void testFindBySessionAndRoundNonExistentReturnsEmpty() {
        assertFalse(repo.findBySessionAndRound("non", 1).isPresent());
    }

    @Test
    public void testFindBySessionIdSortedByRound() {
        repo.save(newContext("s1", 3));
        repo.save(newContext("s1", 1));
        repo.save(newContext("s1", 2));
        repo.save(newContext("s2", 1)); // 不同 session

        List<MpcProtocolContext> forS1 = repo.findBySessionId("s1");
        assertEquals(3, forS1.size());
        // 按轮次升序
        assertEquals(1, forS1.get(0).getRound());
        assertEquals(2, forS1.get(1).getRound());
        assertEquals(3, forS1.get(2).getRound());
    }

    @Test
    public void testDeleteBySessionId() {
        repo.save(newContext("s1", 1));
        repo.save(newContext("s1", 2));
        repo.save(newContext("s2", 1));

        repo.deleteBySessionId("s1");
        assertTrue(repo.findBySessionId("s1").isEmpty());
        assertEquals(1, repo.findBySessionId("s2").size());
    }

    @Test
    public void testSaveNullSessionIdThrows() { assertThrows(IllegalArgumentException.class, () -> {
        MpcProtocolContext ctx = new MpcProtocolContext();
        repo.save(ctx);
        });
    }

    @Test
    public void testSaveOverwritesSameSessionAndRound() {
        repo.save(newContext("s1", 1));
        MpcProtocolContext updated = newContext("s1", 1);
        updated.setParticipantId("p2");
        repo.save(updated);

        Optional<MpcProtocolContext> found = repo.findBySessionAndRound("s1", 1);
        assertEquals(found.get().getParticipantId(), "p2");
    }
}