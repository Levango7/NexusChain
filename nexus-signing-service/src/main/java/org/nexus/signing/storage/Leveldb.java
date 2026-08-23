package org.nexus.signing.storage;

import org.iq80.leveldb.*;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Optional;

/**
 * LevelDB 封装（NoncePool 持久化）。
 *
 * <p>从 {@code org.nexus.wallet.Leveldb.Leveldb}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.storage}。</p>
 *
 * <p>LevelDB 数据文件位于 {@code user.dir/leveldb}，与 exchange-wallet 保持一致，
 * 保证迁移时 Nonce 数据不丢失（设计文档 §7.1 R3 风险缓解）。</p>
 */
@Component
public class Leveldb {

    private static final Logger log = LoggerFactory.getLogger(Leveldb.class);

    private DB db = null;
    private static final Charset CHARSET = Charset.forName("utf-8");
    private static final String path = System.getProperty("user.dir")+File.separator+"leveldb";
    private static final File file = new File(path);
    private static final Options options =new Options();



    public void addPoolDb(String noncepoolval) throws IOException {
        try {
            DBFactory factory = new Iq80DBFactory();
            options.createIfMissing(true);
            this.db = factory.open(file, options);
            byte[] keyByte = "noncepool".getBytes(CHARSET);
            // 会写入磁盘中
            this.db.put(keyByte, noncepoolval.getBytes(CHARSET));
        }catch (Exception e){
            log.error("Failed to write nonce pool to LevelDB", e);
            throw new IOException("Failed to write nonce pool to LevelDB", e);
        }finally {
            if (db != null) {
                try {
                    db.close();
                } catch (IOException e) {
                    log.warn("Failed to close LevelDB after addPoolDb", e);
                }
            }
        }
    }

    public String readFromSnapshot() throws IOException {
        DBFactory factory = new Iq80DBFactory();
        options.createIfMissing(true);
        this.db = factory.open(file, options);
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
            if (db != null) {
                try {
                    db.close();
                } catch (IOException e) {
                    log.warn("Failed to close LevelDB after readFromSnapshot", e);
                }
            }
        }
        return noncepool;
    }

}