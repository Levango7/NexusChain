package org.nexus.db;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.iq80.leveldb.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * LevelDB wrapper with long-lived singleton DB instance and read-write lock.
 *
 * The previous open/put/close-per-operation pattern caused file-lock
 * conflicts and silent write loss under concurrent access (Critical bug).
 * This rewrite uses a single DB instance opened in @PostConstruct
 * and closed in @PreDestroy, protected by a ReentrantReadWriteLock.
 */
@Component
public final class Leveldb {

    private static final Logger log = LoggerFactory.getLogger(Leveldb.class);
    private static final Charset CHARSET = StandardCharsets.UTF_8;

    /** 当前 schema 版本（批次⑥ 第 7 项）：破坏性键空间变更必须递增（docs/db-schema-versioning.md）。 */
    public static final int SCHEMA_VERSION = 1;

    /** 版本 meta 键。 */
    public static final byte[] META_SCHEMA_VERSION_KEY = "__meta/schema_version".getBytes(StandardCharsets.UTF_8);

    private final File file;
    private final Options options;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Long-lived DB instance, opened in init(), closed in destroy(). */
    private volatile DB db;

    public Leveldb(@Value("${nexus.cache-dir}") String cacheDir,
                   @Value("${clear-cache}") boolean clearCache) throws IOException {
        if (cacheDir == null || cacheDir.isEmpty()) {
            cacheDir = System.getProperty("user.dir") + File.separator + "leveldb";
        }
        file = new File(cacheDir);
        if (!file.exists() && !file.mkdirs()) {
            throw new IOException("Cannot create LevelDB directory: " + cacheDir);
        }
        if (!file.isDirectory()) {
            throw new IllegalArgumentException(file.getName() + " is not a directory");
        }
        if (clearCache) {
            FileUtils.deleteDirectoryContents(file);
        }
        this.options = new Options().createIfMissing(true);
    }

    @PostConstruct
    public synchronized void init() throws IOException {
        if (db != null) return;
        DBFactory factory = new Iq80DBFactory();
        db = factory.open(file, options);
        verifySchemaVersion();
        log.info("LevelDB opened: {}", file.getAbsolutePath());
    }

    /**
     * 写 / 校验 schema 版本 meta 键（批次⑥ 第 7 项，2026-10-19）。
     *
     * <p>缺失 → 以当前版本登记（首版采纳既有库）；相等 → 通过；
     * 不相等或值损坏 → {@link SchemaMeta.MismatchException} 拒绝启动。</p>
     */
    private void verifySchemaVersion() {
        byte[] raw = db.get(META_SCHEMA_VERSION_KEY);
        if (raw == null) {
            db.put(META_SCHEMA_VERSION_KEY, String.valueOf(SCHEMA_VERSION).getBytes(CHARSET));
            log.info("LevelDB schema version registered: {}", SCHEMA_VERSION);
            return;
        }
        int existing;
        try {
            existing = Integer.parseInt(new String(raw, CHARSET).trim());
        } catch (NumberFormatException e) {
            throw new SchemaMeta.MismatchException(
                    "LevelDB schema version unreadable (corrupt meta key): '"
                            + new String(raw, CHARSET) + "' — 拒绝启动");
        }
        if (existing == SCHEMA_VERSION) {
            log.info("LevelDB schema version={} OK", SCHEMA_VERSION);
            return;
        }
        String direction = existing < SCHEMA_VERSION ? "OLDER" : "NEWER";
        throw new SchemaMeta.MismatchException("LevelDB schema version mismatch (DATABASE IS "
                + direction + "): database=" + existing + ", code=" + SCHEMA_VERSION
                + " — 见 docs/db-schema-versioning.md，拒绝启动");
    }

    @PreDestroy
    public synchronized void destroy() {
        if (db != null) {
            try {
                db.close();
                log.info("LevelDB closed: {}", file.getAbsolutePath());
            } catch (IOException e) {
                log.error("Error closing LevelDB: {}", file.getAbsolutePath(), e);
            } finally {
                db = null;
            }
        }
    }

    public void addPoolDb(String key, String noncepoolval) {
        write(key.getBytes(CHARSET), noncepoolval.getBytes(CHARSET));
    }

    public String readPoolDb(String key) {
        byte[] res = read(key.getBytes(CHARSET));
        if (res != null && res.length > 0) {
            return new String(res, CHARSET);
        }
        return "";
    }

    public void write(byte[] key, byte[] value) {
        DB current = this.db;
        if (current == null) {
            throw new IllegalStateException("LevelDB not initialised");
        }
        lock.writeLock().lock();
        try {
            current.put(key, value);
        } catch (RuntimeException e) {
            log.error("LevelDB write failed", e);
            throw new RuntimeException("LevelDB write error", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public byte[] read(byte[] key) {
        DB current = this.db;
        if (current == null) {
            throw new IllegalStateException("LevelDB not initialised");
        }
        lock.readLock().lock();
        try {
            return current.get(key);
        } catch (RuntimeException e) {
            log.error("LevelDB read failed", e);
            throw new RuntimeException("LevelDB read error", e);
        } finally {
            lock.readLock().unlock();
        }
    }
}