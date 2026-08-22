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
        log.info("LevelDB opened: {}", file.getAbsolutePath());
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