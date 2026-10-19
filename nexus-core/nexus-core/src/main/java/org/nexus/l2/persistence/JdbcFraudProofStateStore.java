package org.nexus.l2.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.nexus.db.SchemaMeta;
import org.nexus.l2.ChallengeBond;
import org.nexus.l2.FraudProof;
import org.nexus.l2.RollupBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 欺诈证明状态 PostgreSQL 持久化实现（PLAN-014 Phase 4）。
 *
 * <p>接入方式对齐 {@code JdbcPaymentStateStore} / {@code JdbcFinalityStateStore}：
 * {@link JdbcTemplate} + {@link SchemaMeta} 版本登记 + 启动恢复。upsert 采用
 * <b>先 UPDATE、缺行再 INSERT</b> 的可移植写法（INSERT 撞唯一键时重试 UPDATE），
 * 同时兼容 PostgreSQL 与 H2。注意：H2 2.2.224（测试用）只支持
 * {@code ON CONFLICT DO NOTHING}，不支持 {@code DO UPDATE}；{@code MERGE ... KEY()}
 * 则是 H2 专有语法、PostgreSQL 会报 bad SQL grammar。</p>
 *
 * <p>表结构（additive-only，版本 1）：</p>
 * <ul>
 *   <li>{@code l2_fraud_batches}：批次快照（JSON）、提交时间、提交者</li>
 *   <li>{@code l2_fraud_bonds}：挑战者 bond（金额、状态、质押时间）</li>
 *   <li>{@code l2_fraud_conflicts}：first-valid-wins 冲突状态（首个有效挑战者/证明 + 三类名单）</li>
 * </ul>
 *
 * <p><b>并发边界</b>：当前 {@code ChallengeConflictResolver} 为单进程 synchronized；
 * 多副本共享同一 PG 时本实现为 last-writer-wins，不提供分布式锁或乐观版本控制。
 * 完整多副本一致性需另行设计（PLAN-014 Phase 4 记录已标注）。</p>
 *
 * @since 2.74
 */
@Component
@Profile("!dev")
public class JdbcFraudProofStateStore implements FraudProofStateStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcFraudProofStateStore.class);

    /** schema 版本（破坏性变更必须递增并携带迁移脚本，见 docs/db-schema-versioning.md）。 */
    public static final int SCHEMA_VERSION = 1;

    /** 版本登记用 store 标识。 */
    static final String STORE_NAME = "l2_fraud_state";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final JdbcTemplate jdbc;

    public JdbcFraudProofStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        SchemaMeta.ensure(jdbc, STORE_NAME, SCHEMA_VERSION);
        jdbc.execute("CREATE TABLE IF NOT EXISTS l2_fraud_batches ("
                + "batch_id BIGINT PRIMARY KEY, "
                + "submit_time_ms BIGINT NOT NULL, "
                + "submitter VARCHAR(128), "
                + "batch_json TEXT NOT NULL, "
                + "updated_at BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS l2_fraud_bonds ("
                + "challenger_id VARCHAR(128) PRIMARY KEY, "
                + "amount NUMERIC(38,0) NOT NULL, "
                + "status VARCHAR(16) NOT NULL, "
                + "stake_time BIGINT NOT NULL, "
                + "updated_at BIGINT NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS l2_fraud_conflicts ("
                + "batch_id BIGINT PRIMARY KEY, "
                + "first_valid_challenger VARCHAR(128), "
                + "first_valid_proof_json TEXT, "
                + "all_challengers_json TEXT NOT NULL, "
                + "refunded_challengers_json TEXT NOT NULL, "
                + "slashed_challengers_json TEXT NOT NULL, "
                + "updated_at BIGINT NOT NULL)");
        log.info("JdbcFraudProofStateStore: schema initialized (store={}, version={})",
                STORE_NAME, SCHEMA_VERSION);
    }

    @Override
    public void saveBatch(RollupBatch batch, String submitter, Instant submitTime) {
        if (batch == null || batch.getBatchId() == null) {
            throw new IllegalArgumentException("batch and batchId must not be null");
        }
        Instant effectiveSubmitTime = submitTime == null ? Instant.now() : submitTime;
        long updatedAt = System.currentTimeMillis();
        String batchJson = writeJson(batch);
        upsert("UPDATE l2_fraud_batches SET submit_time_ms = ?, submitter = ?, "
                        + "batch_json = ?, updated_at = ? WHERE batch_id = ?",
                new Object[]{effectiveSubmitTime.toEpochMilli(), submitter,
                        batchJson, updatedAt, batch.getBatchId()},
                "INSERT INTO l2_fraud_batches"
                        + "(batch_id, submit_time_ms, submitter, batch_json, updated_at) "
                        + "VALUES(?,?,?,?,?)",
                new Object[]{batch.getBatchId(), effectiveSubmitTime.toEpochMilli(), submitter,
                        batchJson, updatedAt});
    }

    @Override
    public void saveBond(ChallengeBond bond) {
        if (bond == null || bond.getChallengerId() == null) {
            throw new IllegalArgumentException("bond and challengerId must not be null");
        }
        long updatedAt = System.currentTimeMillis();
        upsert("UPDATE l2_fraud_bonds SET amount = ?, status = ?, stake_time = ?, "
                        + "updated_at = ? WHERE challenger_id = ?",
                new Object[]{bond.getAmount(), bond.getStatus().name(), bond.getStakeTime(),
                        updatedAt, bond.getChallengerId()},
                "INSERT INTO l2_fraud_bonds"
                        + "(challenger_id, amount, status, stake_time, updated_at) "
                        + "VALUES(?,?,?,?,?)",
                new Object[]{bond.getChallengerId(), bond.getAmount(), bond.getStatus().name(),
                        bond.getStakeTime(), updatedAt});
    }

    @Override
    public void saveConflict(ConflictRecord conflict) {
        if (conflict == null) {
            throw new IllegalArgumentException("conflict must not be null");
        }
        long updatedAt = System.currentTimeMillis();
        String proofJson = conflict.firstValidProof() == null
                ? null : writeJson(conflict.firstValidProof());
        String allJson = writeJson(conflict.allChallengers());
        String refundedJson = writeJson(conflict.refundedChallengers());
        String slashedJson = writeJson(conflict.slashedChallengers());
        upsert("UPDATE l2_fraud_conflicts SET first_valid_challenger = ?, "
                        + "first_valid_proof_json = ?, all_challengers_json = ?, "
                        + "refunded_challengers_json = ?, slashed_challengers_json = ?, "
                        + "updated_at = ? WHERE batch_id = ?",
                new Object[]{conflict.firstValidChallenger(), proofJson, allJson, refundedJson,
                        slashedJson, updatedAt, conflict.batchId()},
                "INSERT INTO l2_fraud_conflicts"
                        + "(batch_id, first_valid_challenger, first_valid_proof_json, "
                        + "all_challengers_json, refunded_challengers_json, "
                        + "slashed_challengers_json, updated_at) "
                        + "VALUES(?,?,?,?,?,?,?)",
                new Object[]{conflict.batchId(), conflict.firstValidChallenger(), proofJson,
                        allJson, refundedJson, slashedJson, updatedAt});
    }

    @Override
    public void deleteConflict(long batchId) {
        jdbc.update("DELETE FROM l2_fraud_conflicts WHERE batch_id = ?", batchId);
    }

    @Override
    public Snapshot load() {
        List<BatchRecord> batches = jdbc.query(
                "SELECT batch_json, submitter, submit_time_ms FROM l2_fraud_batches",
                (rs, rowNum) -> new BatchRecord(
                        readJson(rs.getString("batch_json"), RollupBatch.class),
                        rs.getString("submitter"),
                        Instant.ofEpochMilli(rs.getLong("submit_time_ms"))));

        List<ChallengeBond> bonds = jdbc.query(
                "SELECT challenger_id, amount, status, stake_time FROM l2_fraud_bonds",
                (rs, rowNum) -> {
                    ChallengeBond bond = new ChallengeBond();
                    bond.setChallengerId(rs.getString("challenger_id"));
                    bond.setAmount(rs.getBigDecimal("amount"));
                    bond.setStatus(ChallengeBond.Status.valueOf(rs.getString("status")));
                    bond.setStakeTime(rs.getLong("stake_time"));
                    return bond;
                });

        List<ConflictRecord> conflicts = jdbc.query(
                "SELECT batch_id, first_valid_challenger, first_valid_proof_json, "
                        + "all_challengers_json, refunded_challengers_json, slashed_challengers_json "
                        + "FROM l2_fraud_conflicts",
                (rs, rowNum) -> {
                    String proofJson = rs.getString("first_valid_proof_json");
                    return new ConflictRecord(
                            rs.getLong("batch_id"),
                            rs.getString("first_valid_challenger"),
                            proofJson == null ? null : readJson(proofJson, FraudProof.class),
                            readStringList(rs.getString("all_challengers_json")),
                            readStringList(rs.getString("refunded_challengers_json")),
                            readStringList(rs.getString("slashed_challengers_json")));
                });

        return new Snapshot(batches, bonds, conflicts);
    }

    /**
     * 可移植 upsert：先 UPDATE；缺行则 INSERT；并发下 INSERT 撞唯一键时重试 UPDATE。
     *
     * <p>不依赖 {@code ON CONFLICT DO UPDATE}（H2 2.2.224 不支持）或
     * {@code MERGE ... KEY()}（PostgreSQL 不支持）。</p>
     */
    private void upsert(String updateSql, Object[] updateArgs, String insertSql, Object[] insertArgs) {
        if (jdbc.update(updateSql, updateArgs) > 0) {
            return;
        }
        try {
            jdbc.update(insertSql, insertArgs);
        } catch (DuplicateKeyException e) {
            // 并发插入竞争：另一端已插入，退回更新
            jdbc.update(updateSql, updateArgs);
        }
    }

    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize fraud-proof state: " + value.getClass().getName(), e);
        }
    }

    private static <T> T readJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt fraud-proof state row (" + type.getSimpleName() + ")", e);
        }
    }

    private static List<String> readStringList(String json) {
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt fraud-proof state row (List<String>)", e);
        }
    }
}
