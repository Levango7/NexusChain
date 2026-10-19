package org.nexus.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次⑥ 第 7 项验收（LevelDB 侧）：新库写版本键 / 同版本重启通过 /
 * 旧值拒绝 / 新值拒绝 / 损坏值拒绝。
 */
class LeveldbSchemaVersionTest {

    @TempDir
    Path tmp;

    private Leveldb open() throws Exception {
        Leveldb db = new Leveldb(tmp.toString(), false);
        db.init();
        return db;
    }

    @Test
    void freshDatabaseRegistersVersionAndRestartPasses() throws Exception {
        Leveldb db1 = open();
        assertArrayEquals(String.valueOf(Leveldb.SCHEMA_VERSION).getBytes(StandardCharsets.UTF_8),
                db1.read(Leveldb.META_SCHEMA_VERSION_KEY), "新库应写入版本键");
        db1.destroy();

        Leveldb db2 = new Leveldb(tmp.toString(), false);
        assertDoesNotThrow(db2::init, "同版本重启应通过");
        db2.destroy();
    }

    @Test
    void olderVersionFailsFast() throws Exception {
        Leveldb db1 = open();
        db1.write(Leveldb.META_SCHEMA_VERSION_KEY, "0".getBytes(StandardCharsets.UTF_8));
        db1.destroy();

        Leveldb db2 = new Leveldb(tmp.toString(), false);
        try {
            SchemaMeta.MismatchException ex = assertThrows(SchemaMeta.MismatchException.class, db2::init);
            assertTrue(ex.getMessage().contains("OLDER"), "应标识数据库过旧");
        } finally {
            db2.destroy();
        }
    }

    @Test
    void newerVersionFailsFast() throws Exception {
        Leveldb db1 = open();
        db1.write(Leveldb.META_SCHEMA_VERSION_KEY, "999".getBytes(StandardCharsets.UTF_8));
        db1.destroy();

        Leveldb db2 = new Leveldb(tmp.toString(), false);
        try {
            SchemaMeta.MismatchException ex = assertThrows(SchemaMeta.MismatchException.class, db2::init);
            assertTrue(ex.getMessage().contains("NEWER"), "应标识防旧代码开新库");
        } finally {
            db2.destroy();
        }
    }

    @Test
    void corruptVersionFailsFast() throws Exception {
        Leveldb db1 = open();
        db1.write(Leveldb.META_SCHEMA_VERSION_KEY, "not-a-number".getBytes(StandardCharsets.UTF_8));
        db1.destroy();

        Leveldb db2 = new Leveldb(tmp.toString(), false);
        try {
            SchemaMeta.MismatchException ex = assertThrows(SchemaMeta.MismatchException.class, db2::init);
            assertTrue(ex.getMessage().contains("unreadable"), "损坏值应给出明确原因");
        } finally {
            db2.destroy();
        }
    }
}
