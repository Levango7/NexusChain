package org.nexus.db;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link JdbcUpsert} 可移植性测试（H2 PostgreSQL 兼容模式）。
 *
 * <p>覆盖插入、更新、幂等插入三条路径；真实 PostgreSQL 契约见
 * {@code PortableUpsertPostgresTest}。</p>
 */
class JdbcUpsertTest {

    private static final String UPDATE =
            "UPDATE t5_kv SET v = ?, n = ? WHERE id = ?";
    private static final String INSERT =
            "INSERT INTO t5_kv(id, v, n) VALUES(?,?,?)";

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:upsert_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE t5_kv (id VARCHAR(32) PRIMARY KEY, v VARCHAR(32), n INT)");
    }

    @Test
    void upsertInsertsWhenAbsent() {
        JdbcUpsert.upsert(jdbc, UPDATE, new Object[]{"alpha", 1, "k1"},
                INSERT, new Object[]{"k1", "alpha", 1});

        assertEquals(1, rowCount("k1"));
        assertEquals("alpha", value("k1"));
    }

    @Test
    void upsertUpdatesWhenPresentWithoutDuplicating() {
        JdbcUpsert.upsert(jdbc, UPDATE, new Object[]{"alpha", 1, "k1"},
                INSERT, new Object[]{"k1", "alpha", 1});
        JdbcUpsert.upsert(jdbc, UPDATE, new Object[]{"beta", 2, "k1"},
                INSERT, new Object[]{"k1", "beta", 2});

        assertEquals(1, rowCount("k1"));
        assertEquals("beta", value("k1"));
        assertEquals(2, jdbc.queryForObject("SELECT n FROM t5_kv WHERE id = 'k1'", Integer.class));
    }

    @Test
    void insertIgnoreIsIdempotentAndKeepsFirstValue() {
        JdbcUpsert.insertIgnore(jdbc,
                "INSERT INTO t5_kv(id, v, n) VALUES(?,?,?)", new Object[]{"k2", "first", 1});
        JdbcUpsert.insertIgnore(jdbc,
                "INSERT INTO t5_kv(id, v, n) VALUES(?,?,?)", new Object[]{"k2", "second", 2});

        assertEquals(1, rowCount("k2"));
        assertEquals("first", value("k2"));
    }

    private int rowCount(String id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t5_kv WHERE id = ?", Integer.class, id);
        return count == null ? 0 : count;
    }

    private String value(String id) {
        return jdbc.queryForObject("SELECT v FROM t5_kv WHERE id = ?", String.class, id);
    }
}
