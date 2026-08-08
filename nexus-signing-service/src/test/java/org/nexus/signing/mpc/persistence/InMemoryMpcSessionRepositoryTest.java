package org.nexus.signing.mpc.persistence;

import org.nexus.signing.mpc.MpcSignSession;
import org.junit.Before;
import org.junit.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link InMemoryMpcSessionRepository} 单元测试。
 */
public class InMemoryMpcSessionRepositoryTest {

    private InMemoryMpcSessionRepository repo;

    @Before
    public void setUp() {
        repo = new InMemoryMpcSessionRepository();
    }

    private MpcSignSession newSession(String id, String walletId, MpcSignSession.SessionStatus status) {
        MpcSignSession s = new MpcSignSession();
        s.setSessionId(id);
        s.setWalletId(walletId);
        s.setStatus(status);
        s.setCreatedAt(LocalDateTime.now());
        return s;
    }

    @Test
    public void testSaveAndFindById() {
        repo.save(newSession("s1", "w1", MpcSignSession.SessionStatus.PENDING));
        Optional<MpcSignSession> found = repo.findById("s1");
        assertTrue(found.isPresent());
        assertEquals("w1", found.get().getWalletId());
    }

    @Test
    public void testFindByIdNonExistentReturnsEmpty() {
        assertFalse(repo.findById("non-existent").isPresent());
    }

    @Test
    public void testFindByWalletId() throws Exception {
        repo.save(newSession("s1", "w1", MpcSignSession.SessionStatus.PENDING));
        Thread.sleep(5);
        repo.save(newSession("s2", "w1", MpcSignSession.SessionStatus.COMPLETED));
        Thread.sleep(5);
        repo.save(newSession("s3", "w2", MpcSignSession.SessionStatus.PENDING));

        List<MpcSignSession> forW1 = repo.findByWalletId("w1");
        assertEquals(2, forW1.size());
        // 按 createdAt 倒序
        assertEquals("s2", forW1.get(0).getSessionId());
        assertEquals("s1", forW1.get(1).getSessionId());
    }

    @Test
    public void testFindByStatus() {
        repo.save(newSession("s1", "w1", MpcSignSession.SessionStatus.PENDING));
        repo.save(newSession("s2", "w1", MpcSignSession.SessionStatus.COMPLETED));
        repo.save(newSession("s3", "w2", MpcSignSession.SessionStatus.PENDING));

        List<MpcSignSession> pending = repo.findByStatus(MpcSignSession.SessionStatus.PENDING);
        assertEquals(2, pending.size());
        List<MpcSignSession> completed = repo.findByStatus(MpcSignSession.SessionStatus.COMPLETED);
        assertEquals(1, completed.size());
    }

    @Test
    public void testDeleteById() {
        repo.save(newSession("s1", "w1", MpcSignSession.SessionStatus.PENDING));
        assertTrue(repo.findById("s1").isPresent());
        repo.deleteById("s1");
        assertFalse(repo.findById("s1").isPresent());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSaveNullSessionIdThrows() {
        repo.save(new MpcSignSession());
    }
}