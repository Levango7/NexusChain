package org.nexus.consortium.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.nexus.common.BatchAbleStore;
import org.nexus.consortium.Start;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/**
 * LevelDB 持久化存储实现。
 * <p>
 * 替换 Start.store() 中的 no-op 实现，使区块/交易数据真正落盘。
 * <ul>
 *   <li>key 与 value 均通过 {@link Start#MAPPER} 以 JSON 序列化后写入 LevelDB；</li>
 *   <li>所有读写操作均加 synchronized，保证线程安全；</li>
 *   <li>{@link PreDestroy} 钩子在 Spring 容器关闭时优雅关闭 DB。</li>
 * </ul>
 */
@Slf4j
public class LevelDbStore implements BatchAbleStore<Object, Object>, AutoCloseable {

    private static final ObjectMapper MAPPER = Start.MAPPER;

    private final DB db;

    /**
     * 以给定数据目录构造 LevelDbStore。
     *
     * @param dataDir 数据目录路径，若不存在会自动创建
     */
    public LevelDbStore(String dataDir) {
        File dir = new File(dataDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("failed to create leveldb data dir: " + dataDir);
        }
        Options options = new Options()
                .createIfMissing(true)
                .maxOpenFiles(1000)
                .blockSize(4096)
                .writeBufferSize(4 * 1024 * 1024)
                .cacheSize(8 * 1024 * 1024);
        try {
            // 优先使用纯 Java 实现（org.iq80.leveldb:leveldb:0.12），避免 JNI 平台依赖
            this.db = Iq80DBFactory.factory.open(dir, options);
        } catch (IOException e) {
            throw new IllegalStateException("failed to open leveldb at " + dataDir, e);
        }
        log.info("LevelDbStore opened at {}", dir.getAbsolutePath());
    }

    @Override
    public synchronized void put(Object k, Object v) {
        if (k == null || v == null) return;
        try {
            db.put(encodeKey(k), encodeValue(v));
        } catch (DBException | IOException e) {
            log.error("leveldb put failed, key={}", k, e);
        }
    }

    @Override
    public synchronized void putIfAbsent(Object k, Object v) {
        if (k == null || v == null) return;
        try {
            byte[] keyBytes = encodeKey(k);
            if (db.get(keyBytes) == null) {
                db.put(keyBytes, encodeValue(v));
            }
        } catch (DBException | IOException e) {
            log.error("leveldb putIfAbsent failed, key={}", k, e);
        }
    }

    @Override
    public synchronized Optional<Object> get(Object k) {
        if (k == null) return Optional.empty();
        try {
            byte[] raw = db.get(encodeKey(k));
            if (raw == null) return Optional.empty();
            return Optional.of(decodeValue(raw));
        } catch (DBException | IOException e) {
            log.error("leveldb get failed, key={}", k, e);
            return Optional.empty();
        }
    }

    @Override
    public synchronized void remove(Object k) {
        if (k == null) return;
        try {
            db.delete(encodeKey(k));
        } catch (DBException | IOException e) {
            log.error("leveldb remove failed, key={}", k, e);
        }
    }

    @Override
    @SuppressWarnings("rawtypes")
    public synchronized void updateBatch(Map rows) {
        if (rows == null || rows.isEmpty()) return;
        WriteBatch batch = db.createWriteBatch();
        try {
            for (Object entryObj : rows.entrySet()) {
                Map.Entry entry = (Map.Entry) entryObj;
                Object k = entry.getKey();
                Object v = entry.getValue();
                if (k == null || v == null) continue;
                try {
                    batch.put(encodeKey(k), encodeValue(v));
                } catch (IOException e) {
                    log.error("leveldb batch encode failed, key={}", k, e);
                }
            }
            db.write(batch);
        } catch (DBException e) {
            log.error("leveldb updateBatch write failed", e);
        } finally {
            try {
                batch.close();
            } catch (IOException e) {
                log.warn("leveldb batch close failed", e);
            }
        }
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        if (db != null) {
            try {
                db.close();
                log.info("LevelDbStore closed");
            } catch (IOException e) {
                log.warn("leveldb close failed", e);
            }
        }
    }

    /**
     * 将 key 序列化为字节数组。优先使用对象 toString 后 UTF-8 编码，
     * 避免 JSON 序列化对 key 引入额外换行/缩进噪声。
     */
    private byte[] encodeKey(Object k) throws IOException {
        if (k instanceof byte[]) return (byte[]) k;
        return k.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 将 value 序列化为 JSON 字节数组。
     */
    private byte[] encodeValue(Object v) throws IOException {
        if (v instanceof byte[]) return (byte[]) v;
        return MAPPER.writeValueAsBytes(v);
    }

    /**
     * 将 LevelDB 中读出的字节数组反序列化为 Object。
     */
    private Object decodeValue(byte[] raw) throws IOException {
        return MAPPER.readValue(raw, Object.class);
    }
}