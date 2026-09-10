package org.nexus.signing.storage;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link Leveldb} 单元测试。
 *
 * <p>LevelDB 数据文件位于 {@code user.dir/leveldb}（静态路径，无法覆盖）。
 * 测试写入一个 noncepool 值后读取快照，验证 round-trip 一致性。</p>
 *
 * <p>注：本测试会产生磁盘 I/O 副作用（在工程根目录创建 leveldb/ 目录），
 * 这是 Leveldb 类的固有设计（path 为 static final）。</p>
 *
 * <p>Top5 修复（2026-09-10）：Leveldb 改常驻实例 + AutoCloseable 后，
 * 测试直构的多实例必须 try-with-resources 逐个关闭——否则共享同一
 * static 文件路径的前一实例持有文件锁，新实例 open 抛
 * OverlappingFileLockException。</p>
 */
public class LeveldbTest {

    @Test
    public void testAddAndReadRoundTrip() throws IOException {
        try (Leveldb db = new Leveldb()) {
            String value = "test-noncepool-value-" + System.currentTimeMillis();
            db.addPoolDb(value);
            String read = db.readFromSnapshot();
            assertEquals(value, read);
        }
    }

    @Test
    public void testAddEmptyString() throws IOException {
        try (Leveldb db = new Leveldb()) {
            db.addPoolDb("");
            // 空字符串写入后读取应返回空（key 存在但 value 为空）
            String read = db.readFromSnapshot();
            assertEquals(read, "");
        }
    }

    @Test
    public void testAddJsonContent() throws IOException {
        try (Leveldb db = new Leveldb()) {
            String json = "{\"address1\":{\"1\":{\"tranHash\":\"0xabc\",\"nonce\":1,\"datetime\":1234567890}}}";
            db.addPoolDb(json);
            String read = db.readFromSnapshot();
            assertEquals(json, read);
        }
    }

    @Test
    public void testReadFromSnapshot_returnsString() throws IOException {
        try (Leveldb db = new Leveldb()) {
            db.addPoolDb("snapshot-test");
            String result = db.readFromSnapshot();
            assertNotNull(result);
        }
    }

    @Test
    public void testMultipleWritesLastValueWins() throws IOException {
        try (Leveldb db = new Leveldb()) {
            db.addPoolDb("first-value");
            db.addPoolDb("second-value");
            db.addPoolDb("third-value");
            String read = db.readFromSnapshot();
            assertEquals(read, "third-value");
        }
    }
}
