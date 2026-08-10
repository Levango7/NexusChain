package org.nexus.signing.mpc.wal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Write-Ahead Log：消息持久化与崩溃恢复回放。
 *
 * <p>每条出站消息在发送前先追加写入 WAL 文件，发送成功后可标记为已提交。
 * 节点崩溃重启后调用 {@link #recover()} 读取未提交的条目并回放，保证
 * 消息不丢失（at-least-once 语义，配合 {@code MessageDeduplicator} 实现 exactly-once）。</p>
 *
 * <p><b>文件格式</b>（每行一条记录，UTF-8）：</p>
 * <pre>
 *   {epochMillis}|{sessionId}|{messageId}|{committed}|{messageBase64}
 * </pre>
 *
 * <p>{@code committed=0} 表示未提交（需回放），{@code committed=1} 表示已提交。
 * 文件按 {@code sessionId} 命名，会话结束后可归档/删除。</p>
 *
 * <p><b>线程安全</b>：所有写操作 synchronized 串行化（WAL 写入是热点但
 * 单条消息不大，串行可接受；高吞吐场景可改为 per-session 单线程 writer）。</p>
 */
@Component
public class WriteAheadLog {

    private static final Logger log = LoggerFactory.getLogger(WriteAheadLog.class);

    private final Path walDir;

    /**
     * 构造 WAL。
     *
     * @param walDirPath WAL 目录路径
     */
    public WriteAheadLog(@Value("${nexus.mpc.wal.dir:./mpc-wal}") String walDirPath) {
        this.walDir = Paths.get(walDirPath);
        try {
            Files.createDirectories(walDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create WAL dir: " + walDir, e);
        }
        log.info("WriteAheadLog initialized: dir={}", walDir.toAbsolutePath());
    }

    /**
     * 追加一条未提交的 WAL 记录。
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     * @param messageBytes 消息二进制（{@code MpcMessage.toByteArray()}）
     * @return 该记录的序号（行号，1-based）
     */
    public synchronized long append(String sessionId, String messageId, byte[] messageBytes) {
        try {
            Path file = walFor(sessionId);
            String record = formatRecord(messageId, false, messageBytes);
            Files.write(file, record.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            long seq = countLines(file);
            log.debug("WAL appended: session={}, msg={}, seq={}", sessionId, messageId, seq);
            return seq;
        } catch (IOException e) {
            throw new IllegalStateException("WAL append failed: " + sessionId, e);
        }
    }

    /**
     * 标记一条消息为已提交（发送成功后调用）。
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     */
    public synchronized void commit(String sessionId, String messageId) {
        try {
            Path file = walFor(sessionId);
            if (!Files.exists(file)) return;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> updated = new ArrayList<>(lines.size());
            for (String line : lines) {
                WalEntry entry = parseEntry(line);
                if (entry != null && entry.messageId.equals(messageId) && !entry.committed) {
                    updated.add(formatRecord(messageId, true, entry.messageBytes));
                } else {
                    updated.add(line);
                }
            }
            Files.write(file, updated, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("WAL committed: session={}, msg={}", sessionId, messageId);
        } catch (IOException e) {
            throw new IllegalStateException("WAL commit failed: " + sessionId, e);
        }
    }

    /**
     * 恢复未提交的消息（崩溃重启后调用）。
     *
     * @param sessionId 会话 ID
     * @return 未提交的消息二进制列表（按追加顺序）
     */
    public synchronized List<byte[]> recover(String sessionId) {
        List<byte[]> result = new ArrayList<>();
        try {
            Path file = walFor(sessionId);
            if (!Files.exists(file)) return result;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                WalEntry entry = parseEntry(line);
                if (entry != null && !entry.committed) {
                    result.add(entry.messageBytes);
                }
            }
            log.info("WAL recovered: session={}, uncommitted={}", sessionId, result.size());
        } catch (IOException e) {
            throw new IllegalStateException("WAL recover failed: " + sessionId, e);
        }
        return result;
    }

    /**
     * 列出所有有 WAL 文件的会话 ID。
     *
     * @return 会话 ID 列表
     */
    public List<String> listSessions() {
        List<String> sessions = new ArrayList<>();
        try (var stream = Files.list(walDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".wal"))
                    .forEach(p -> {
                        String name = p.getFileName().toString();
                        sessions.add(name.substring(0, name.length() - 4));
                    });
        } catch (IOException e) {
            log.warn("WAL listSessions failed: {}", e.getMessage());
        }
        return sessions;
    }

    /**
     * 归档（删除）指定会话的 WAL 文件。
     *
     * @param sessionId 会话 ID
     */
    public synchronized void archive(String sessionId) {
        try {
            Files.deleteIfExists(walFor(sessionId));
            log.info("WAL archived: session={}", sessionId);
        } catch (IOException e) {
            log.warn("WAL archive failed: session={}, err={}", sessionId, e.getMessage());
        }
    }

    private Path walFor(String sessionId) {
        return walDir.resolve(
                sessionId.replaceAll("[^A-Za-z0-9_-]", "_") + ".wal");
    }

    private String formatRecord(String messageId, boolean committed, byte[] bytes) {
        String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
        return Instant.now().toEpochMilli() + "|" + messageId + "|"
                + (committed ? "1" : "0") + "|" + b64;
    }

    private WalEntry parseEntry(String line) {
        String[] parts = line.split("\\|", 4);
        if (parts.length < 4) return null;
        try {
            long ts = Long.parseLong(parts[0]);
            String messageId = parts[1];
            boolean committed = "1".equals(parts[2]);
            byte[] bytes = java.util.Base64.getDecoder().decode(parts[3]);
            return new WalEntry(ts, messageId, committed, bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private long countLines(Path file) throws IOException {
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.count();
        }
    }

    private static final class WalEntry {
        final long ts;
        final String messageId;
        final boolean committed;
        final byte[] messageBytes;

        WalEntry(long ts, String messageId, boolean committed, byte[] messageBytes) {
            this.ts = ts;
            this.messageId = messageId;
            this.committed = committed;
            this.messageBytes = messageBytes;
        }
    }
}