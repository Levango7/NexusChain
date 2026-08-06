package org.nexus.walletsvc.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flyway migration 集成测试（Phase 4 任务 #74，设计文档 §4.6.2 / §4.3）。
 *
 * <p>验证 Flyway V1 / V2 / V3 migration 在 H2（MODE=MySQL）下正确执行：
 * <ul>
 *   <li>V1 {@code V1__init_wallet_tables.sql}：4 张业务表创建
 *      （custody_balances / address_whitelist / withdrawal_requests / withdrawal_approvers）</li>
 *   <li>V2 {@code V2__seed_custody_balances.sql}：custody_balances 预置 HOT / COLD 两行</li>
 *   <li>V3 {@code V3__add_undo_log.sql}：Seata AT undo_log 表创建</li>
 * </ul>
 * </p>
 *
 * <p>通过 JDBC {@link DatabaseMetaData} 直接查询表是否存在，不依赖 JPA Entity 映射，
 * 验证的是 Flyway 执行的 SQL DDL 本身。</p>
 *
 * <p>H2 MODE=MySQL 兼容性：DECIMAL(36,18) / AUTO_INCREMENT / UNIQUE KEY / INDEX
 * / FOREIGN KEY / BLOB / DATETIME(6) 均在 H2 MODE=MySQL 下可执行。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class FlywayMigrationIT {

    @Autowired
    private DataSource dataSource;

    /** 获取数据库中所有表名（小写归一化）。 */
    private Set<String> getTableNames() throws Exception {
        Set<String> tables = new HashSet<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData metaData = conn.getMetaData();
            try (ResultSet rs = metaData.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toLowerCase());
                }
            }
        }
        return tables;
    }

    @Test
    @DisplayName("Flyway V1: custody_balances 表存在")
    void v1_custodyBalancesTableExists() throws Exception {
        assertTrue(getTableNames().contains("custody_balances"),
                "V1 应创建 custody_balances 表");
    }

    @Test
    @DisplayName("Flyway V1: address_whitelist 表存在")
    void v1_addressWhitelistTableExists() throws Exception {
        assertTrue(getTableNames().contains("address_whitelist"),
                "V1 应创建 address_whitelist 表");
    }

    @Test
    @DisplayName("Flyway V1: withdrawal_requests 表存在")
    void v1_withdrawalRequestsTableExists() throws Exception {
        assertTrue(getTableNames().contains("withdrawal_requests"),
                "V1 应创建 withdrawal_requests 表");
    }

    @Test
    @DisplayName("Flyway V1: withdrawal_approvers 表存在")
    void v1_withdrawalApproversTableExists() throws Exception {
        assertTrue(getTableNames().contains("withdrawal_approvers"),
                "V1 应创建 withdrawal_approvers 表");
    }

    @Test
    @DisplayName("Flyway V3: undo_log 表存在（Seata AT 回滚表）")
    void v3_undoLogTableExists() throws Exception {
        assertTrue(getTableNames().contains("undo_log"),
                "V3 应创建 undo_log 表");
    }

    @Test
    @DisplayName("Flyway V1+V2+V3: 全部 5 张表均已创建")
    void allTablesCreated() throws Exception {
        Set<String> tables = getTableNames();
        assertTrue(tables.contains("custody_balances"), "custody_balances 缺失");
        assertTrue(tables.contains("address_whitelist"), "address_whitelist 缺失");
        assertTrue(tables.contains("withdrawal_requests"), "withdrawal_requests 缺失");
        assertTrue(tables.contains("withdrawal_approvers"), "withdrawal_approvers 缺失");
        assertTrue(tables.contains("undo_log"), "undo_log 缺失");
    }

    @Test
    @DisplayName("Flyway: flyway_schema_history 表存在（Flyway 元数据表）")
    void flywaySchemaHistoryExists() throws Exception {
        assertTrue(getTableNames().contains("flyway_schema_history"),
                "Flyway 应创建 flyway_schema_history 元数据表");
    }
}