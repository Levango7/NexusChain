package org.nexus.signing.mpc.wal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link WriteAheadLog} 单元测试。
 */
public class WriteAheadLogTest {

    private Path walDir;
    private WriteAheadLog wal;

    @Before
    public void setUp() throws Exception {
        walDir = Files.createTempDirectory("mpc-wal-test");
        wal = new WriteAheadLog(walDir.toString());
    }

    @After
    public void tearDown() throws Exception {
        if (walDir != null && Files.exists(walDir)) {
            Files.walk(walDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        }
    }

    @Test
    public void testAppendAndRecoverUncommitted() {
        byte[] data1 = "message-1".getBytes();

        long seq1 = wal.append("s1", "msg-1", data1);
        assertTrue(seq1 > 0);

        List<byte[]> recovered = wal.recover("s1");
        assertEquals(1, recovered.size());
    }

    @Test
    public void testAppendCommitAppendRecover() {
        // append → commit（重写文件加换行）→ append → recover
        wal.append("s1", "msg-1", "data-1".getBytes());
        wal.commit("s1", "msg-1");
        wal.append("s1", "msg-2", "data-2".getBytes());

        List<byte[]> recovered = wal.recover("s1");
        assertEquals(1, recovered.size()); // msg-1 已提交，msg-2 未提交
    }

    @Test
    public void testCommitRemovesFromRecover() {
        byte[] data = "message".getBytes();
        wal.append("s1", "msg-1", data);
        wal.commit("s1", "msg-1");

        List<byte[]> recovered = wal.recover("s1");
        assertEquals(0, recovered.size());
    }

    @Test
    public void testRecoverNonExistentSessionReturnsEmpty() {
        List<byte[]> recovered = wal.recover("non-existent");
        assertTrue(recovered.isEmpty());
    }

    @Test
    public void testCommitNonExistentFileNoThrow() {
        wal.commit("non-existent", "msg-1"); // 不抛异常
    }

    @Test
    public void testListSessions() {
        wal.append("s1", "msg-1", "data".getBytes());
        wal.append("s2", "msg-2", "data".getBytes());
        List<String> sessions = wal.listSessions();
        assertTrue(sessions.contains("s1"));
        assertTrue(sessions.contains("s2"));
        assertEquals(2, sessions.size());
    }

    @Test
    public void testArchiveDeletesFile() {
        wal.append("s1", "msg-1", "data".getBytes());
        assertTrue(wal.listSessions().contains("s1"));
        wal.archive("s1");
        assertFalse(wal.listSessions().contains("s1"));
    }

    @Test
    public void testArchiveNonExistentNoThrow() {
        wal.archive("non-existent"); // 不抛异常
    }

    @Test
    public void testMultipleSessionsIndependent() {
        wal.append("s1", "msg-1", "data-1".getBytes());
        wal.append("s2", "msg-2", "data-2".getBytes());
        wal.commit("s1", "msg-1");

        assertEquals(0, wal.recover("s1").size());
        assertEquals(1, wal.recover("s2").size());
    }

    @Test
    public void testSessionIdWithSpecialCharactersSanitized() {
        wal.append("session/with|special", "msg-1", "data".getBytes());
        List<String> sessions = wal.listSessions();
        // 文件名应被清理（特殊字符替换为 _）
        assertTrue(sessions.size() >= 1);
    }

    @Test
    public void testRecoverAfterPartialCommit() {
        // append + commit msg-1（commit 重写文件加换行符）
        wal.append("s1", "msg-1", "data-1".getBytes());
        wal.commit("s1", "msg-1");
        // append msg-2（新行，未提交）
        wal.append("s1", "msg-2", "data-2".getBytes());

        List<byte[]> recovered = wal.recover("s1");
        assertEquals(1, recovered.size()); // msg-1 已提交，仅 msg-2 未提交
    }
}