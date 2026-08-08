package org.nexus.signing.mpc.persistence;

import org.nexus.signing.mpc.MpcWallet;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link InMemoryMpcWalletRepository} 单元测试。
 */
public class InMemoryMpcWalletRepositoryTest {

    private InMemoryMpcWalletRepository repo;

    @Before
    public void setUp() {
        repo = new InMemoryMpcWalletRepository();
    }

    private MpcWallet newWallet(String id, List<String> participants) {
        MpcWallet w = new MpcWallet();
        w.setWalletId(id);
        w.setParticipants(participants);
        w.setThreshold(2);
        return w;
    }

    @Test
    public void testSaveAndFindById() {
        MpcWallet w = newWallet("w1", List.of("p1", "p2"));
        repo.save(w);
        Optional<MpcWallet> found = repo.findById("w1");
        assertTrue(found.isPresent());
        assertEquals("w1", found.get().getWalletId());
    }

    @Test
    public void testFindByIdNonExistentReturnsEmpty() {
        assertFalse(repo.findById("non-existent").isPresent());
    }

    @Test
    public void testFindAll() {
        repo.save(newWallet("w1", List.of("p1")));
        repo.save(newWallet("w2", List.of("p2")));
        List<MpcWallet> all = repo.findAll();
        assertEquals(2, all.size());
    }

    @Test
    public void testFindByParticipant() {
        repo.save(newWallet("w1", List.of("p1", "p2")));
        repo.save(newWallet("w2", List.of("p2", "p3")));
        repo.save(newWallet("w3", List.of("p3")));
        List<MpcWallet> forP2 = repo.findByParticipant("p2");
        assertEquals(2, forP2.size());
    }

    @Test
    public void testExistsById() {
        repo.save(newWallet("w1", List.of("p1")));
        assertTrue(repo.existsById("w1"));
        assertFalse(repo.existsById("w2"));
    }

    @Test
    public void testDeleteById() {
        repo.save(newWallet("w1", List.of("p1")));
        assertTrue(repo.existsById("w1"));
        repo.deleteById("w1");
        assertFalse(repo.existsById("w1"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSaveNullWalletIdThrows() {
        MpcWallet w = new MpcWallet();
        repo.save(w);
    }

    @Test
    public void testSaveOverwritesExisting() {
        repo.save(newWallet("w1", List.of("p1")));
        MpcWallet updated = newWallet("w1", List.of("p1", "p2", "p3"));
        repo.save(updated);
        Optional<MpcWallet> found = repo.findById("w1");
        assertEquals(3, found.get().getParticipants().size());
    }
}