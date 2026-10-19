package org.nexus.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次⑥ 第 7 项验收：schema 版本登记"缺失→写入 / 相符→通过 / 不符→拒绝"三分支。
 *
 * <p>H2 内存库；每用例独立库名防串扰。</p>
 */
class SchemaMetaTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:schematest_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
    }

    private void seedVersion(String store, int version) {
        jdbc.execute("CREATE TABLE " + SchemaMeta.TABLE + " ("
                + "store VARCHAR(64) PRIMARY KEY, version INT NOT NULL, updated_at BIGINT NOT NULL)");
        jdbc.update("INSERT INTO " + SchemaMeta.TABLE + "(store, version, updated_at) VALUES(?,?,?)",
                store, version, 0L);
    }

    @Test
    void missingRegistrationIsAdoptedAtCurrentVersion() {
        SchemaMeta.ensure(jdbc, "payment_state", 1);

        Integer v = jdbc.queryForObject(
                "SELECT version FROM " + SchemaMeta.TABLE + " WHERE store = ?", Integer.class, "payment_state");
        assertEquals(1, v, "缺失登记 → 以当前版本写入");
        assertDoesNotThrow(() -> SchemaMeta.ensure(jdbc, "payment_state", 1), "重复调用幂等通过");
    }

    @Test
    void equalVersionPasses() {
        seedVersion("payment_state", 1);
        assertDoesNotThrow(() -> SchemaMeta.ensure(jdbc, "payment_state", 1));
    }

    @Test
    void olderDatabaseFailsFastWithMigrationGuidance() {
        seedVersion("payment_state", 0);

        SchemaMeta.MismatchException ex = assertThrows(SchemaMeta.MismatchException.class,
                () -> SchemaMeta.ensure(jdbc, "payment_state", 1));
        assertTrue(ex.getMessage().contains("DATABASE IS OLDER"), "应标识：数据库过旧方向");
        assertTrue(ex.getMessage().contains("db-schema-versioning"), "应输出迁移指引");
    }

    @Test
    void newerDatabaseFailsFast() {
        seedVersion("payment_state", 2);

        SchemaMeta.MismatchException ex = assertThrows(SchemaMeta.MismatchException.class,
                () -> SchemaMeta.ensure(jdbc, "payment_state", 1));
        assertTrue(ex.getMessage().contains("DATABASE IS NEWER"), "应标识：防旧代码开新库方向");
    }

    @Test
    void storesAreIndependent() {
        SchemaMeta.ensure(jdbc, "payment_state", 1);
        SchemaMeta.ensure(jdbc, "finality_state", 3);

        assertDoesNotThrow(() -> SchemaMeta.ensure(jdbc, "finality_state", 3));
        Integer v = jdbc.queryForObject(
                "SELECT version FROM " + SchemaMeta.TABLE + " WHERE store = ?", Integer.class, "payment_state");
        assertEquals(1, v, "其他 store 的登记互不影响");
    }
}
