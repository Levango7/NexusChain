package org.nexus.consensus.finality.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.core.Block;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-069：迁移脚本的「脏行判定口径」必须与生产代码<b>逐字一致</b>的门禁。
 *
 * <h2>缺口（为什么需要它）</h2>
 * <p>{@code migrations/pg/001-finality-quarantine-invalid-checkpoint-hash.sql}
 * 会把 {@code finality_votes} 里「检查点哈希不是 32 字节」的行搬进隔离表。
 * 判定口径一旦与生产不一致，就是**双向**破坏：</p>
 * <ul>
 *   <li>脚本<b>更宽</b>（例如只查长度 {@code < 64}）⇒ 把合法行搬走 ⇒
 *       真实投票记录被"清理"，最终性事实受损，且回滚前生产侧看不到这些票；</li>
 *   <li>脚本<b>更严</b>（例如只查 {@code = 3}）⇒ 漏掉其他畸形行 ⇒ 声称"清完了"
 *       而库里仍有脏行，与本切片的目标直接矛盾。</li>
 * </ul>
 * <p>这类缺陷单跑任何一侧都发现不了：脚本在 PG 里跑、判定在 Java 里跑，
 * 两边都"自己绿"。所以必须有一条断言把两侧绑在一起。</p>
 *
 * <h2>生产口径（两侧必须同时满足才算合法）</h2>
 * <ol>
 *   <li>{@link org.nexus.consensus.finality.FinalityGadget} 侧：
 *       {@code hasValidCheckpointHash(h)} ⇒ {@code h != null && h.length == Block.HASH_SIZE}；</li>
 *   <li>{@link JdbcFinalityStateStore} 侧：写入用 {@code HexFormat.of().formatHex(bytes)}，
 *       所以 32 字节 ⇒ 恰好 64 个小写 hex 字符；读回用 {@code hexToBytes()}，
 *       非 hex 会解成 {@code null} ⇒ 后续校验必然失败。</li>
 * </ol>
 * <p>⇒ 一条库行合法 ⟺ 长度为 64 <b>且</b> 全为 hex 字符。</p>
 *
 * @since 2.80.0
 */
class FinalityDirtyRowMigrationContractTest {

    private static final String MIGRATIONS_DIR = "migrations/pg";
    private static final String FORWARD_SQL = "001-finality-quarantine-invalid-checkpoint-hash.sql";
    private static final String ROLLBACK_SQL = "001-finality-quarantine-invalid-checkpoint-hash.rollback.sql";

    /** 与 SQL 里 {@code checkpoint_hash ~ '^[0-9a-fA-F]{64}$'} 等价的 Java 正则。 */
    private static final Pattern SQL_HEX64 = Pattern.compile("^[0-9a-fA-F]{64}$");

    // ------------------------------------------------------------ 口径一致性

    @Test
    @DisplayName("合法/非法判定：Java 侧口径与 SQL 侧口径必须对同一批样本给出相同结论")
    void javaAndSqlPredicatesAgreeOnAllSamples() {
        List<String> samples = List.of(
                hex(32),                          // 合法
                hex(32).toUpperCase(),            // 合法（大小写不影响 hex 解析）
                "010203",                         // 非法：3 字节（PLAN-068 实测形状）
                hex(31),                          // 非法：63 字符
                hex(32) + "b",                    // 非法：65 字符
                "z".repeat(64),                   // 非法：长度对但非 hex
                "",                               // 非法：空
                "0f".repeat(31) + "zz"            // 非法：62 位 hex + 2 位非 hex
        );

        for (String sample : samples) {
            assertEquals(sqlPredicate(sample), productionPredicate(sample),
                    "口径不一致会让迁移脚本删错/漏删行：" + abbreviate(sample));
        }
    }

    @Test
    @DisplayName("每一处判定都必须同时校验长度与 hex 字符集（逐语句，不允许靠别处文本蒙混过关）")
    void everyPredicateChecksBothLengthAndCharset() {
        String sql = read(FORWARD_SQL);

        String insertStmt = statement(sql, "INSERT INTO finality_votes_quarantine");
        String deleteStmt = statement(sql, "DELETE FROM finality_votes v");
        String verifyStmt = statement(sql, "remaining_invalid");

        for (Map.Entry<String, String> entry : Map.of(
                "§2 INSERT 搬运判定", insertStmt,
                "§3 DELETE 删除判定", deleteStmt).entrySet()) {
            String stmt = entry.getValue();
            assertTrue(stmt.contains("length(") && stmt.contains("= 64"),
                    entry.getKey() + " 必须按 length = 64 判定（不是 < 64、不是 <= 64）：" + stmt);
            assertFalse(stmt.contains("< 64") || stmt.contains("<= 64"),
                    entry.getKey() + " 不得退化成『长度小于 64』——那会漏掉 65 位与非 hex 的脏行：" + stmt);
            assertTrue(stmt.contains("^[0-9a-fA-F]{64}$"),
                    entry.getKey() + " 必须校验 hex 字符集，否则非 hex 的 64 位行会被当成合法：" + stmt);
        }

        assertTrue(verifyStmt.contains("^[0-9a-fA-F]{64}$"),
                "§4 自检的 remaining_invalid 必须用同一口径，否则自检会假报『清完了』");
    }

    @Test
    @DisplayName("32 字节哈希的 hex 表示恰好 64 字符（钉住 Block.HASH_SIZE 与 SQL 里写死的 64 的关系）")
    void hashSizeAndSqlLiteralStayInSync() {
        assertEquals(64, Block.HASH_SIZE * 2,
                "SQL 里写死的 64 必须等于 Block.HASH_SIZE * 2；改了 HASH_SIZE 就必须同步改脚本");
        assertEquals(64, HexFormat.of().formatHex(new byte[Block.HASH_SIZE]).length());
    }

    // ------------------------------------------------------------ 安全属性

    @Test
    @DisplayName("正向脚本先隔离后删除，且删除只作用于已确认隔离的行（不会静默丢数据）")
    void forwardScriptQuarantinesBeforeDeleting() {
        String sql = read(FORWARD_SQL);

        int insertAt = sql.indexOf("INSERT INTO finality_votes_quarantine");
        int deleteAt = sql.indexOf("DELETE FROM finality_votes");
        assertTrue(insertAt > 0, "正向脚本必须把脏行搬进隔离表");
        assertTrue(deleteAt > 0, "正向脚本必须把脏行从 finality_votes 移除");
        assertTrue(insertAt < deleteAt,
                "必须先 INSERT 到隔离表、再 DELETE —— 反了就是不可回滚的数据丢失");

        // DELETE 必须带 EXISTS(隔离表) 双保险：即使 §2 因故少搬，也不会丢行。
        String deleteStmt = statement(sql, "DELETE FROM finality_votes v");
        assertTrue(deleteStmt.contains("EXISTS") && deleteStmt.contains("finality_votes_quarantine"),
                "DELETE 必须限定在『已确认存在于隔离表』的行，否则搬漏的行直接消失：" + deleteStmt);
        assertTrue(deleteStmt.contains("q.epoch = v.epoch")
                        && deleteStmt.contains("q.checkpoint_hash = v.checkpoint_hash")
                        && deleteStmt.contains("q.validator = v.validator"),
                "EXISTS 必须按三列主键匹配，不能只按部分列（否则会误删同 epoch 其他验证人的行）："
                        + deleteStmt);

        assertTrue(sql.contains("ON CONFLICT (epoch, checkpoint_hash, validator) DO NOTHING"),
                "搬运必须幂等：重复执行不得因主键冲突中断");
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS finality_votes_quarantine"),
                "隔离表必须用 IF NOT EXISTS 创建（幂等）");
    }

    @Test
    @DisplayName("回滚脚本把行原样搬回，且不删除隔离表（审计痕迹必须保留）")
    void rollbackRestoresRowsAndKeepsAuditTrail() {
        String sql = read(ROLLBACK_SQL);

        assertTrue(sql.contains("INSERT INTO finality_votes"),
                "回滚必须把行搬回 finality_votes");
        assertTrue(sql.contains("FROM finality_votes_quarantine"),
                "回滚必须从隔离表取数据");
        assertTrue(sql.contains("migration_id = 'PLAN-069/001'"),
                "回滚只能恢复本迁移隔离的行，不得顺带恢复其他来源");

        String withoutComments = stripSqlComments(sql);
        assertFalse(withoutComments.toUpperCase().contains("DELETE FROM FINALITY_VOTES_QUARANTINE"),
                "回滚不得删除隔离表内容——审计痕迹一旦删掉就无法证明'曾发生过什么'");
        assertFalse(withoutComments.toUpperCase().contains("DROP TABLE"),
                "回滚不得 DROP 隔离表");
    }

    @Test
    @DisplayName("隔离表带审计列：reason / quarantined_at / quarantined_by / migration_id")
    void quarantineTableCarriesAuditColumns() {
        String sql = read(FORWARD_SQL);
        for (String column : List.of("reason", "quarantined_at", "quarantined_by", "migration_id")) {
            assertTrue(sql.contains(column),
                    "隔离表缺少审计列 " + column + "——没有它就无法回答'谁在何时为什么动了数据'");
        }
        assertTrue(sql.contains("migration_id    VARCHAR(64)  NOT NULL"),
                "migration_id 必须是 NOT NULL（回滚按它收窄范围）");
    }

    // ------------------------------------------------------ PLAN-071: checkpoints

    @Test
    @DisplayName("finality_checkpoints 也被隔离：独立隔离表 + 同一套口径 + 先搬后删")
    void checkpointsAreQuarantinedToo() {
        String sql = read(FORWARD_SQL);

        // 独立隔离表（不用 votes 表硬塞 validator 哨兵值——审计表不能撒谎）
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS finality_checkpoints_quarantine"),
                "finality_checkpoints 需要自己的隔离表：它的主键没有 validator 列，"
                        + "硬塞进 votes 隔离表就得给 validator 编哨兵值，审计表一旦说谎就无法可靠反查");
        assertTrue(sql.contains("PRIMARY KEY (epoch, checkpoint_hash)"),
                "checkpoints 隔离表主键必须与源表同构 (epoch, checkpoint_hash)");

        String insertStmt = statement(sql, "INSERT INTO finality_checkpoints_quarantine");
        String deleteStmt = statement(sql, "DELETE FROM finality_checkpoints c");

        // 与 votes 完全相同的判定口径（长度 = 64 且 hex 字符集）
        for (String stmt : List.of(insertStmt, deleteStmt)) {
            assertTrue(stmt.contains("length(") && stmt.contains("= 64"),
                    "checkpoints 判定必须按 length = 64：" + stmt);
            assertFalse(stmt.contains("< 64") || stmt.contains("<= 64"),
                    "checkpoints 判定不得退化成『长度小于 64』——会漏掉 65 位与非 hex 行：" + stmt);
            assertTrue(stmt.contains("^[0-9a-fA-F]{64}$"),
                    "checkpoints 判定必须校验 hex 字符集：" + stmt);
        }

        // 先搬后删 + EXISTS 双保险
        int cpInsertAt = sql.indexOf("INSERT INTO finality_checkpoints_quarantine");
        int cpDeleteAt = sql.indexOf("DELETE FROM finality_checkpoints c");
        assertTrue(cpInsertAt > 0 && cpDeleteAt > 0, "checkpoints 的搬运/移除两段都必须存在");
        assertTrue(cpInsertAt < cpDeleteAt,
                "checkpoints 也必须先 INSERT 到隔离表、再 DELETE");
        assertTrue(deleteStmt.contains("EXISTS")
                        && deleteStmt.contains("finality_checkpoints_quarantine"),
                "checkpoints 的 DELETE 必须带 EXISTS 隔离表双保险：" + deleteStmt);
        assertTrue(sql.contains("ON CONFLICT (epoch, checkpoint_hash) DO NOTHING"),
                "checkpoints 搬运必须幂等（按自己的主键 ON CONFLICT）");

        // 审计表的 migration_id 要能区分来源，回滚据此收窄
        assertTrue(sql.contains("'PLAN-071/001'"),
                "checkpoints 隔离行必须带独立 migration_id，否则回滚无法区分来源");
    }

    @Test
    @DisplayName("回滚按来源分别搬回：checkpoints 回 checkpoints、votes 回 votes")
    void rollbackRestoresEachTableBySource() {
        String sql = read(ROLLBACK_SQL);

        assertTrue(sql.contains("INSERT INTO finality_checkpoints"),
                "回滚必须把检查点隔离行搬回 finality_checkpoints");
        assertTrue(sql.contains("FROM finality_checkpoints_quarantine"),
                "回滚必须从检查点隔离表取数据");
        assertTrue(sql.contains("migration_id = 'PLAN-071/001'"),
                "回滚只能恢复本切片的 checkpoints 隔离行（按来源收窄）");

        // 关键：checkpoints 的行**不得**被搬回 votes 表（列都不存在，且语义错位）
        String cpRestore = statement(sql, "INSERT INTO finality_checkpoints");
        assertFalse(cpRestore.contains("validator"),
                "检查点行不得写进 finality_votes（该表还有 validator 列，会错位）");

        String withoutComments = stripSqlComments(sql);
        assertFalse(withoutComments.toUpperCase().contains("DELETE FROM FINALITY_VOTES_QUARANTINE"),
                "回滚不得删除隔离表内容（审计痕迹）");
        assertFalse(withoutComments.toUpperCase().contains("DELETE FROM FINALITY_CHECKPOINTS_QUARANTINE"),
                "回滚不得删除检查点隔离表内容（审计痕迹）");
        assertFalse(withoutComments.toUpperCase().contains("DROP TABLE"), "回滚不得 DROP 隔离表");
    }


    // ------------------------------------------------------------ helpers

    /** SQL 侧的判定：{@code length = 64 AND ~ '^[0-9a-fA-F]{64}$'}。 */
    private static boolean sqlPredicate(String checkpointHash) {
        return checkpointHash != null
                && checkpointHash.length() == 64
                && SQL_HEX64.matcher(checkpointHash).matches();
    }

    /**
     * 生产侧的判定：先按 {@code hexToBytes()} 能否解析，再按 32 字节长度校验
     * （{@code hasValidCheckpointHash}）。两者都过才算合法。
     */
    private static boolean productionPredicate(String checkpointHash) {
        if (checkpointHash == null || checkpointHash.isEmpty()) {
            return false;
        }
        byte[] bytes;
        try {
            bytes = HexFormat.of().parseHex(checkpointHash);
        } catch (IllegalArgumentException e) {
            return false;   // 与 JdbcFinalityStateStore.hexToBytes() 的降级一致
        }
        return bytes != null && bytes.length == Block.HASH_SIZE;
    }

    private static String hex(int bytes) {
        return HexFormat.of().formatHex(new byte[bytes]);
    }

    /** 截取以 {@code marker} 开头、到下一个分号为止的那条语句（用于逐语句断言）。 */
    private static String statement(String sql, String marker) {
        int start = sql.indexOf(marker);
        assertTrue(start >= 0, "找不到语句起点：" + marker);
        int end = sql.indexOf(';', start);
        assertTrue(end > start, "语句未以分号结束：" + marker);
        return sql.substring(start, end + 1);
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 72 ? "\"" + s + "\" (len=" + s.length() + ")"
                : "\"" + s.substring(0, 72) + "...\" (len=" + s.length() + ")";
    }

    private static String read(String fileName) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(MIGRATIONS_DIR).resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new AssertionError("无法读取 " + candidate, e);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("未找到迁移脚本 " + MIGRATIONS_DIR + "/" + fileName
                + "（仓库根定位失败）");
    }

    /** 去掉 {@code --} 行注释，避免注释里提到 {@code DELETE} 造成假红。 */
    private static String stripSqlComments(String sql) {
        StringBuilder out = new StringBuilder();
        for (String line : sql.split("\n")) {
            int idx = line.indexOf("--");
            out.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return out.toString();
    }
}
