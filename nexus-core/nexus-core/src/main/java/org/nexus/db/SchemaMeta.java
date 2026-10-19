package org.nexus.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 共享 DB schema 版本登记（批次⑥ 第 7 项，2026-10-19；PLAN-014 Phase 0）。
 *
 * <p>背景：PG 侧各 store 此前仅 {@code CREATE TABLE IF NOT EXISTS}、无版本记录——
 * 旧代码可静默打开新库（反之亦然），破坏性 schema 变更无护栏（见
 * {@code docs/audit/2026-10-19-nexus-core-narrative-reality-routes.md} §7 与
 * {@code docs/db-schema-versioning.md}）。</p>
 *
 * <p>语义（每 store 一行，登记表 {@value #TABLE}）：</p>
 * <ul>
 *   <li>登记表 / 记录缺失 → 以 {@code currentVersion} 登记（首版采纳既有库）</li>
 *   <li>版本相等 → 通过</li>
 *   <li>数据库版本<b>低于</b>代码 → 抛 {@link MismatchException}（拒绝启动，先执行迁移脚本）</li>
 *   <li>数据库版本<b>高于</b>代码 → 抛 {@link MismatchException}（拒绝启动，防旧代码开新库）</li>
 * </ul>
 *
 * <p>迁移政策（additive-only、迁移脚本命名与执行）见 {@code docs/db-schema-versioning.md}。</p>
 */
public final class SchemaMeta {

    private static final Logger log = LoggerFactory.getLogger(SchemaMeta.class);

    /** 登记表名（所有 JDBC store 共享；LevelDB 侧对应 meta 键，见 {@code org.nexus.db.Leveldb}）。 */
    public static final String TABLE = "nexus_schema_meta";

    private SchemaMeta() {
    }

    /**
     * 确保 {@code store} 的 schema 版本与 {@code currentVersion} 一致；不一致时 fail-fast。
     *
     * @param jdbc           目标数据源（H2 / PostgreSQL）
     * @param store          store 标识（如 {@code payment_state}）
     * @param currentVersion 当前代码期望的 schema 版本（破坏性变更时必须递增并携带迁移脚本）
     * @throws MismatchException 版本不匹配（数据库过旧或过新）——调用方必须让启动失败
     */
    public static void ensure(JdbcTemplate jdbc, String store, int currentVersion) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "store VARCHAR(64) PRIMARY KEY, "
                + "version INT NOT NULL, "
                + "updated_at BIGINT NOT NULL)");

        List<Integer> found = jdbc.query(
                "SELECT version FROM " + TABLE + " WHERE store = ?",
                (rs, rowNum) -> rs.getInt(1), store);

        if (found.isEmpty()) {
            jdbc.update("INSERT INTO " + TABLE + "(store, version, updated_at) VALUES(?,?,?)",
                    store, currentVersion, System.currentTimeMillis());
            log.info("SchemaMeta: store={} registered at version={}", store, currentVersion);
            return;
        }

        int existing = found.get(0);
        if (existing == currentVersion) {
            log.info("SchemaMeta: store={} version={} OK", store, currentVersion);
            return;
        }
        if (existing < currentVersion) {
            throw new MismatchException("Schema version mismatch for store=" + store
                    + ": database=" + existing + " < code=" + currentVersion
                    + " — DATABASE IS OLDER: 请先按 docs/db-schema-versioning.md 执行迁移脚本，拒绝启动");
        }
        throw new MismatchException("Schema version mismatch for store=" + store
                + ": database=" + existing + " > code=" + currentVersion
                + " — DATABASE IS NEWER: 代码过旧（防旧代码开新库），请部署匹配版本，拒绝启动");
    }

    /** schema 版本不匹配（fail-fast 专用异常；调用方不得吞没）。 */
    public static class MismatchException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        public MismatchException(String message) {
            super(message);
        }
    }
}
