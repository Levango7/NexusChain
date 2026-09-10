package org.nexus.signing.storage;

import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

/**
 * LevelDB 封装（NoncePool 持久化）。
 *
 * <p>从 {@code org.nexus.wallet.Leveldb.Leveldb}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.storage}。</p>
 *
 * <p>LevelDB 数据文件位于 {@code user.dir/leveldb}，与 exchange-wallet 保持一致，
 * 保证迁移时 Nonce 数据不丢失（设计文档 §7.1 R3 风险缓解）。</p>
 *
 * <p>并发修复（质量审查 Top5，2026-09-10）：原实现每次 addPoolDb 都
 * open→put→close 整个 LevelDB——open/close 开销极大（每次全量 manifest
 * 重放），且并发调用会在同一文件上触发 LockException（LevelDB 单进程
 * 独占锁）。现改为 Bean 生命周期常驻实例：@PostConstruct 打开一次，
 * @PreDestroy 关闭；写操作 synchronized（DB.put 线程安全，但与
 * readFromSnapshot 的迭代器并发仍需互斥——NoncePool 的 add/remove 本身
 * 已在方法级 synchronized，此处兜底防直调）。</p>
 */
@Component
public class Leveldb implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Leveldb.class);

    private DB db = null;
    private static final Charset CHARSET = Charset.forName("utf-8");
    private static final String path = System.getProperty("user.dir")+File.separator+"leveldb";
    private static final File file = new File(path);
    // 注：Options 不再 static 共享——iq80 LevelDB 的 Options 在 open() 后会被
    // 库内部持有并复用其内部句柄；跨 open 复用同一 Options 实例会导致后续
    // open 失败（实测 testMultipleWritesLastValueWins 串行多实例场景）。
    // 每次 open 一律 new Options。

    /** 常驻打开（Top5 修复）：Bean 初始化时打开一次，进程结束才关闭。 */
    @PostConstruct
    void openDb() throws IOException {
        openIfClosed();
    }

    /**
     * 幂等打开：已打开则跳过。生产路径由 @PostConstruct 首次打开；
     * 测试直构（new Leveldb()，无 Spring 生命周期）在首次读写时自动打开。
     */
    private synchronized void openIfClosed() throws IOException {
        if (this.db != null) {
            return;
        }
        try {
            DBFactory factory = new Iq80DBFactory();
            Options options = new Options();
            options.createIfMissing(true);
            this.db = factory.open(file, options);
        } catch (Exception e) {
            log.error("Failed to open LevelDB at {}", path, e);
            throw new IOException("Failed to open LevelDB at " + path, e);
        }
    }

    /** 常驻关闭（Top5 修复）：Bean 销毁时关闭，进程退出不泄漏文件锁。 */
    @PreDestroy
    void closeDb() {
        close();
    }

    /**
     * 显式关闭（实现 AutoCloseable）：生产由 Spring @PreDestroy 调用；
     * 测试直构多实例场景（共享 static 文件路径）必须显式关闭前一个
     * 实例，否则新实例 open 抛 OverlappingFileLockException。
     */
    @Override
    public synchronized void close() {
        if (db != null) {
            try {
                db.close();
            } catch (IOException e) {
                log.warn("Failed to close LevelDB on shutdown", e);
            }
            db = null;
        }
    }

    public synchronized void addPoolDb(String noncepoolval) throws IOException {
        openIfClosed();
        try {
            byte[] keyByte = "noncepool".getBytes(CHARSET);
            // 会写入磁盘中
            this.db.put(keyByte, noncepoolval.getBytes(CHARSET));
        } catch (Exception e) {
            log.error("Failed to write nonce pool to LevelDB", e);
            throw new IOException("Failed to write nonce pool to LevelDB", e);
        }
    }

    public synchronized String readFromSnapshot() throws IOException {
        openIfClosed();
        String noncepool = "";
        Snapshot snapshot = null;
        DBIterator it = null;
        try {
            // 读取当前快照，重启服务仍能读取，说明快照持久化至磁盘，
            snapshot = this.db.getSnapshot();
            // 读取操作
            ReadOptions readOptions = new ReadOptions();
            // 遍历中swap出来的数据，不应该保存在memtable中。
            readOptions.fillCache(false);
            // 默认snapshot为当前
            readOptions.snapshot(snapshot);

            it = db.iterator(readOptions);
            while (it.hasNext()) {
                Map.Entry<byte[], byte[]> entry = (Map.Entry<byte[], byte[]>) it
                        .next();
                String key = new String(entry.getKey(), CHARSET);
                String value = new String(entry.getValue(), CHARSET);
                if(key.equals("noncepool")){
                    noncepool = value;
                }
            }
        } catch (Exception e) {
            log.error("Failed to read nonce pool snapshot from LevelDB", e);
        } finally {
            // 关闭迭代器与快照，避免资源泄漏
            if (it != null) {
                try {
                    it.close();
                } catch (IOException e) {
                    log.warn("Failed to close LevelDB iterator", e);
                }
            }
            if (snapshot != null) {
                try {
                    snapshot.close();
                } catch (Exception e) {
                    log.warn("Failed to close LevelDB snapshot", e);
                }
            }
        }
        return noncepool;
    }

}
