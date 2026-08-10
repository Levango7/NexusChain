package org.nexus.signing.mpc.persistence;

import org.nexus.signing.mpc.MpcKeyShare;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EncryptedFileKeyShareStore} 单元测试。
 */
public class EncryptedFileKeyShareStoreTest {

    private Path storageDir;
    private EncryptedFileKeyShareStore store;

    @BeforeEach
    public void setUp() throws Exception {
        storageDir = Files.createTempDirectory("mpc-keyshare-test");
        String kek = Base64.getEncoder().encodeToString(new byte[32]);
        store = new EncryptedFileKeyShareStore(storageDir.toString(), kek);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (storageDir != null && Files.exists(storageDir)) {
            Files.walk(storageDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        }
    }

    @Test
    public void testSaveAndLoadRoundTrip() {
        MpcKeyShare share = new MpcKeyShare("p1", "private-hex", "public-hex", "paillier-hex");
        store.save(share);

        Optional<MpcKeyShare> loaded = store.load("p1");
        assertTrue(loaded.isPresent());
        assertEquals(loaded.get().getParticipantId(), "p1");
        assertEquals(loaded.get().getPrivateShareHex(), "private-hex");
        assertEquals(loaded.get().getPublicShareHex(), "public-hex");
        assertEquals(loaded.get().getPaillierPublicKeyHex(), "paillier-hex");
    }

    @Test
    public void testSaveAndLoadWithNullPaillier() {
        MpcKeyShare share = new MpcKeyShare("p1", "priv", "pub", null);
        store.save(share);
        Optional<MpcKeyShare> loaded = store.load("p1");
        assertTrue(loaded.isPresent());
        assertEquals(null, loaded.get().getPaillierPublicKeyHex());
    }

    @Test
    public void testLoadNonExistentReturnsEmpty() {
        Optional<MpcKeyShare> loaded = store.load("non-existent");
        assertFalse(loaded.isPresent());
    }

    @Test
    public void testExists() {
        store.save(new MpcKeyShare("p1", "priv", "pub", null));
        assertTrue(store.exists("p1"));
        assertFalse(store.exists("p2"));
    }

    @Test
    public void testDelete() {
        store.save(new MpcKeyShare("p1", "priv", "pub", null));
        assertTrue(store.exists("p1"));
        store.delete("p1");
        assertFalse(store.exists("p1"));
    }

    @Test
    public void testListParticipantIds() {
        store.save(new MpcKeyShare("p1", "priv", "pub", null));
        store.save(new MpcKeyShare("p2", "priv", "pub", null));
        List<String> ids = store.listParticipantIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("p1"));
        assertTrue(ids.contains("p2"));
    }

    @Test
    public void testSpecialCharacterParticipantIdSanitized() {
        // 使用不含 |（分隔符）的特殊字符；/ 和空格会被文件名 sanitize 替换为 _
        // 但 participantId 本身在加密内容中完整保留
        store.save(new MpcKeyShare("p1-with.special", "priv", "pub", null));
        assertTrue(store.exists("p1-with.special"));
        Optional<MpcKeyShare> loaded = store.load("p1-with.special");
        assertTrue(loaded.isPresent());
        assertEquals(loaded.get().getParticipantId(), "p1-with.special");
    }

    @Test
    public void testNullKekAndNoEnvThrows() { assertThrows(IllegalStateException.class, () -> {
        if (System.getenv("NEXUS_MPC_KEK") == null) {
            new EncryptedFileKeyShareStore(storageDir.toString(), null);
        } else {
            throw new IllegalStateException("test skipped: env NEXUS_MPC_KEK set");
        }
        });
    }

    @Test
    public void testShortKekThrows() { assertThrows(IllegalStateException.class, () -> {
        String shortKek = Base64.getEncoder().encodeToString(new byte[16]);
        new EncryptedFileKeyShareStore(storageDir.toString(), shortKek);
        });
    }
}