package org.nexus.consensus.finality.persistence;

import jakarta.annotation.PostConstruct;
import org.nexus.db.JdbcUpsert;
import org.nexus.db.SchemaMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC（Postgres/H2）版最终性状态存储。
 *
 * <p>对齐 {@code JdbcPaymentStateStore} 模式：{@link JdbcTemplate} + 建表 + 可移植 upsert。
 * 仅在 <b>写入时落库</b>，读取走内存重建缓存（启动时 {@code loadAll*}）。</p>
 *
 * <p>表结构：</p>
 * <ul>
 *   <li>{@code finality_votes(epoch BIGINT, checkpoint_hash VARCHAR(128), validator VARCHAR(128), PRIMARY KEY(epoch, checkpoint_hash, validator))}</li>
 *   <li>{@code finality_checkpoints(epoch BIGINT, checkpoint_hash VARCHAR(128), PRIMARY KEY(epoch, checkpoint_hash))}</li>
 * </ul>
 */
@Component
@Profile("!dev")
@ConditionalOnProperty(name = "nexus.finality.store", havingValue = "jdbc")
public class JdbcFinalityStateStore implements FinalityStateStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcFinalityStateStore.class);

    /** schema 版本（批次⑥ 第 7 项）：破坏性变更必须递增并携带迁移脚本（docs/db-schema-versioning.md）。 */
    public static final int SCHEMA_VERSION = 1;

    /** 版本登记用 store 标识。 */
    static final String STORE_NAME = "finality_state";

    private final JdbcTemplate jdbc;

    /**
     * 启动时从库重建的缓存（读路径不触库）。
     *
     * <p>⚠️ <b>禁止为这两个缓存添加 TTL</b>（2026-09-17 审查明确记录）。</p>
     *
     * <p>它们与 {@code JdbcPaymentStateStore} 中的缓存<b>性质不同</b>：后者是
     * 可过期的旁路缓存（未命中会回源查库），而本类持有的是<b>启动时加载的终局
     * 状态快照</b>，{@link #isFinalized} 与 {@link #getVotes} 均<b>不回源查库</b>。
     * 若加上 TTL，条目过期后已终局的检查点会被判为「未终局」，进而可能被重复
     * 终局或拒绝合法终局 —— 这是<b>共识安全违规</b>，而非性能退化。</p>
     *
     * <p>已知局限（本次未修，需架构决策）：多副本部署下缺乏跨实例失效机制 ——
     * 实例 A 终局某检查点后，实例 B 的内存快照不会更新，B 将持续认为该检查点
     * 未终局。可选方案：① 订阅终局事件做增量更新；② 定时 {@code reload()}；
     * ③ 读路径改为查库 + 本地缓存。三者各有代价，需结合部署形态决定，
     * 故不在本次修复范围内。</p>
     */
    private final Map<String, Set<String>> voteCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> finalizedCache = new ConcurrentHashMap<>();

    /**
     * 带签名的完整 Vote 对象缓存（PLAN-018）。
     *
     * <p>只在启动时由 {@link #reload()} 填充，供 {@link #loadAllVoteObjects()} 返回；
     * 与 {@link #voteCache} 一样是"启动快照"，<b>不设 TTL</b>（同 {@link #voteCache} 的理由：
     * 条目过期会让已具备验签条件的检查点重新变得不可验签）。</p>
     */
    private final Map<String, List<org.nexus.consensus.finality.Vote>> voteObjectCache =
            new ConcurrentHashMap<>();

    public JdbcFinalityStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        SchemaMeta.ensure(jdbc, STORE_NAME, SCHEMA_VERSION);
        jdbc.execute("CREATE TABLE IF NOT EXISTS finality_votes ("
            + "epoch BIGINT NOT NULL, checkpoint_hash VARCHAR(128) NOT NULL, "
            + "validator VARCHAR(128) NOT NULL, "
            + "PRIMARY KEY (epoch, checkpoint_hash, validator))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS finality_checkpoints ("
            + "epoch BIGINT NOT NULL, checkpoint_hash VARCHAR(128) NOT NULL, "
            + "PRIMARY KEY (epoch, checkpoint_hash))");
        // PLAN-018（additive-only，docs/db-schema-versioning.md §三.1）：
        // ① finality_votes 增列 signature/public_key —— 让重启后能恢复完整 Vote 对象
        //    （否则聚合验签无票可验，检查点永远无法在重启后补最终化）。
        // ② finality_vote_outbox —— 持久化"写库意图"：票还没写进 finality_votes，
        //    但必须记住它，进程重启后不丢。
        // 两处 DDL 都必须**幂等**（IF NOT EXISTS）：SchemaMeta 只登记版本号，
        // 不会校验已登记为 version=1 的旧库到底有没有这两列/这张表。
        // 兼容：PG 9.6+ 与 H2 2.x 都支持 ADD COLUMN IF NOT EXISTS。
        jdbc.execute("ALTER TABLE finality_votes ADD COLUMN IF NOT EXISTS signature VARCHAR(256)");
        jdbc.execute("ALTER TABLE finality_votes ADD COLUMN IF NOT EXISTS public_key VARCHAR(256)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS finality_vote_outbox ("
            + "epoch BIGINT NOT NULL, checkpoint_hash VARCHAR(128) NOT NULL, "
            + "validator VARCHAR(128) NOT NULL, origin VARCHAR(16) NOT NULL, "
            + "signature VARCHAR(256), public_key VARCHAR(256), "
            + "created_at BIGINT NOT NULL, attempts INT NOT NULL DEFAULT 0, "
            + "last_error VARCHAR(512), "
            + "PRIMARY KEY (epoch, checkpoint_hash, validator, origin))");
        log.info("JdbcFinalityStateStore: schema initialized");
        reload();
    }

    /** 从库重建内存缓存（启动/恢复用）。 */
    private void reload() {
        try {
            // 注意：query(String, RowCallbackHandler) 的回调是"每行调用一次"，
            // 回调内不能再调用 rs.next()（旧实现这样写会跳过隔行；单行时一行都读不到）。
            jdbc.query("SELECT epoch, checkpoint_hash, validator FROM finality_votes", rs -> {
                String key = rs.getLong("epoch") + "|" + rs.getString("checkpoint_hash");
                voteCache.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                        .add(rs.getString("validator"));
            });
            jdbc.query("SELECT epoch, checkpoint_hash FROM finality_checkpoints", rs -> {
                finalizedCache.put(rs.getLong("epoch") + "|" + rs.getString("checkpoint_hash"),
                        Boolean.TRUE);
            });
            // PLAN-018：恢复带签名的完整 Vote 对象（供重启后聚合验签）。
            // 只取有签名的行 —— 迁移前写入的历史行没有签名，无法参与验签。
            jdbc.query("SELECT epoch, checkpoint_hash, validator, signature, public_key "
                    + "FROM finality_votes WHERE signature IS NOT NULL", rs -> {
                String ck = rs.getString("checkpoint_hash");
                String key = rs.getLong("epoch") + "|" + ck;
                byte[] sig = hexToBytes(rs.getString("signature"));
                byte[] pub = hexToBytes(rs.getString("public_key"));
                byte[] checkpoint = hexToBytes(ck);
                voteObjectCache.computeIfAbsent(key, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                        .add(new org.nexus.consensus.finality.Vote(
                                rs.getLong("epoch"), checkpoint, rs.getString("validator"), sig, pub));
            });
            log.info("JdbcFinalityStateStore: reloaded {} vote records, {} finalized checkpoints, {} signed votes",
                    voteCache.size(), finalizedCache.size(), voteObjectCache.size());
        } catch (RuntimeException e) {
            // 最终性状态是“已确认不可逆结算”的安全事实；加载失败时若静默降级为空缓存，
            // 节点会以为旧检查点未最终化，存在重复终局/破坏安全前提的风险。
            // 因此这里 fail-fast：宁可拒绝启动，也不带病参与共识。
            throw new IllegalStateException(
                    "Failed to reload finality state from database; refusing to start with an empty finality cache",
                    e);
        }
    }

    @Override
    public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress) {
        recordVote(epoch, checkpointHash, validatorAddress, null, null);
    }

    @Override
    public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress,
                           byte[] signature, byte[] publicKey) {
        String ck = hashStr(checkpointHash);
        // PLAN-018：签名与公钥一并落库（重启后据此恢复完整 Vote 对象做聚合验签）。
        // 用可移植 upsert：先 UPDATE（补齐后到的签名），未命中再 INSERT。
        JdbcUpsert.upsert(jdbc,
                "UPDATE finality_votes SET signature=?, public_key=? "
                        + "WHERE epoch=? AND checkpoint_hash=? AND validator=?",
                new Object[]{hexOrNull(signature), hexOrNull(publicKey), epoch, ck, validatorAddress},
                "INSERT INTO finality_votes(epoch, checkpoint_hash, validator, signature, public_key) "
                        + "VALUES(?,?,?,?,?)",
                new Object[]{epoch, ck, validatorAddress, hexOrNull(signature), hexOrNull(publicKey)});
        voteCache.computeIfAbsent(key(epoch, ck), k -> ConcurrentHashMap.newKeySet()).add(validatorAddress);
        if (signature != null && signature.length > 0) {
            // PLAN-025：anti-entropy 补票会重复投递同一张票，按验证人去重替换，
            // 否则 voteObjectCache 的 List 会随补票轮次无限增长（每次 add 都追加）。
            java.util.List<org.nexus.consensus.finality.Vote> list = voteObjectCache
                    .computeIfAbsent(key(epoch, ck), k -> new java.util.concurrent.CopyOnWriteArrayList<>());
            org.nexus.consensus.finality.Vote incoming = new org.nexus.consensus.finality.Vote(
                    epoch, checkpointHash, validatorAddress, signature, publicKey);
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                if (validatorAddress.equals(list.get(i).getValidatorAddress())) {
                    list.set(i, incoming);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                list.add(incoming);
            }
        }
    }

    @Override
    public Map<String, List<org.nexus.consensus.finality.Vote>> loadAllVoteObjects() {
        return Map.copyOf(voteObjectCache);
    }

    @Override
    public void enqueuePendingVote(FinalityOutboxEntry entry) {
        String ck = hashStr(entry.getCheckpointHash());
        JdbcUpsert.upsert(jdbc,
                "UPDATE finality_vote_outbox SET signature=?, public_key=?, attempts=?, last_error=? "
                        + "WHERE epoch=? AND checkpoint_hash=? AND validator=? AND origin=?",
                new Object[]{hexOrNull(entry.getSignature()), hexOrNull(entry.getPublicKey()),
                        entry.getAttempts(), entry.getLastError(),
                        entry.getEpoch(), ck, entry.getValidatorAddress(), entry.getOrigin().code()},
                "INSERT INTO finality_vote_outbox(epoch, checkpoint_hash, validator, origin, signature, "
                        + "public_key, created_at, attempts, last_error) VALUES(?,?,?,?,?,?,?,?,?)",
                new Object[]{entry.getEpoch(), ck, entry.getValidatorAddress(), entry.getOrigin().code(),
                        hexOrNull(entry.getSignature()), hexOrNull(entry.getPublicKey()),
                        entry.getCreatedAt(), entry.getAttempts(), entry.getLastError()});
    }

    @Override
    public List<FinalityOutboxEntry> loadPendingVotes(int limit) {
        int cap = limit <= 0 ? 64 : limit;
        List<FinalityOutboxEntry> out = new java.util.ArrayList<>();
        jdbc.query("SELECT epoch, checkpoint_hash, validator, origin, signature, public_key, "
                        + "created_at, attempts, last_error FROM finality_vote_outbox "
                        + "ORDER BY created_at ASC LIMIT " + cap,
                rs -> {
                    out.add(new FinalityOutboxEntry(
                            rs.getLong("epoch"),
                            hexToBytes(rs.getString("checkpoint_hash")),
                            rs.getString("validator"),
                            hexToBytes(rs.getString("signature")),
                            hexToBytes(rs.getString("public_key")),
                            FinalityVoteOrigin.fromCode(rs.getString("origin")),
                            rs.getLong("created_at"),
                            rs.getInt("attempts"),
                            rs.getString("last_error")));
                });
        return out;
    }

    @Override
    public void removePendingVote(FinalityOutboxEntry entry) {
        jdbc.update("DELETE FROM finality_vote_outbox WHERE epoch=? AND checkpoint_hash=? "
                        + "AND validator=? AND origin=?",
                entry.getEpoch(), hashStr(entry.getCheckpointHash()), entry.getValidatorAddress(),
                entry.getOrigin().code());
    }

    @Override
    public void markPendingVoteFailure(FinalityOutboxEntry entry, String error) {
        jdbc.update("UPDATE finality_vote_outbox SET attempts=attempts+1, last_error=? "
                        + "WHERE epoch=? AND checkpoint_hash=? AND validator=? AND origin=?",
                error == null ? "" : (error.length() > 500 ? error.substring(0, 500) : error),
                entry.getEpoch(), hashStr(entry.getCheckpointHash()), entry.getValidatorAddress(),
                entry.getOrigin().code());
    }

    @Override
    public Map<String, List<org.nexus.consensus.finality.Vote>> loadVoteObjectsSince(long fromEpoch) {
        Map<String, List<org.nexus.consensus.finality.Vote>> out = new LinkedHashMap<>();
        try {
            // 只取带签名的行（迁移前的历史行没有签名，无法参与验签）。
            jdbc.query("SELECT epoch, checkpoint_hash, validator, signature, public_key "
                            + "FROM finality_votes WHERE epoch >= ? AND signature IS NOT NULL",
                    rs -> {
                        String ck = rs.getString("checkpoint_hash");
                        String key = rs.getLong("epoch") + "|" + ck;
                        byte[] sig = hexToBytes(rs.getString("signature"));
                        byte[] pub = hexToBytes(rs.getString("public_key"));
                        byte[] checkpoint = hexToBytes(ck);
                        out.computeIfAbsent(key,
                                        k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                                .add(new org.nexus.consensus.finality.Vote(
                                        rs.getLong("epoch"), checkpoint, rs.getString("validator"), sig, pub));
                    }, fromEpoch);
        } catch (RuntimeException e) {
            log.warn("JdbcFinalityStateStore: load votes since {} failed: {}", fromEpoch, e.getMessage());
        }
        return out;
    }

    /**
     * PLAN-025：补票响应用 —— 排序与截断下沉到 SQL，避免 from=0 时把整张
     * {@code finality_votes} 读进内存（长链上可达数万行）。
     */
    @Override
    public List<org.nexus.consensus.finality.Vote> loadLatestVoteObjects(long fromEpoch, int limit) {
        List<org.nexus.consensus.finality.Vote> out = new java.util.ArrayList<>();
        try {
            jdbc.query("SELECT epoch, checkpoint_hash, validator, signature, public_key "
                            + "FROM finality_votes WHERE epoch >= ? AND signature IS NOT NULL "
                            + "ORDER BY epoch DESC LIMIT ?",
                    rs -> {
                        out.add(new org.nexus.consensus.finality.Vote(
                                rs.getLong("epoch"),
                                hexToBytes(rs.getString("checkpoint_hash")),
                                rs.getString("validator"),
                                hexToBytes(rs.getString("signature")),
                                hexToBytes(rs.getString("public_key"))));
                    },
                    fromEpoch, Math.max(1, limit));
        } catch (RuntimeException e) {
            log.warn("JdbcFinalityStateStore: load latest votes (from={}, limit={}) failed: {}",
                    fromEpoch, limit, e.getMessage());
        }
        return out;
    }

    /**
     * PLAN-057：补票游标分页的响应端查询——{@code ORDER BY epoch ASC LIMIT ?}。
     *
     * <p>与 {@link #loadLatestVoteObjects(long, int)} 的唯一差别是排序方向。
     * 分页请求方每轮从"上一轮终点 + 1"继续，必须拿到<b>最老</b>的 N 条；
     * 用降序会让每轮都拿回同一批最新票，中段缺口结构上补不回来。</p>
     */
    @Override
    public List<org.nexus.consensus.finality.Vote> loadVoteObjectsAscending(long fromEpoch, int limit) {
        List<org.nexus.consensus.finality.Vote> out = new java.util.ArrayList<>();
        try {
            jdbc.query("SELECT epoch, checkpoint_hash, validator, signature, public_key "
                            + "FROM finality_votes WHERE epoch >= ? AND signature IS NOT NULL "
                            + "ORDER BY epoch ASC LIMIT ?",
                    rs -> {
                        out.add(new org.nexus.consensus.finality.Vote(
                                rs.getLong("epoch"),
                                hexToBytes(rs.getString("checkpoint_hash")),
                                rs.getString("validator"),
                                hexToBytes(rs.getString("signature")),
                                hexToBytes(rs.getString("public_key"))));
                    },
                    fromEpoch, Math.max(1, limit));
        } catch (RuntimeException e) {
            log.warn("JdbcFinalityStateStore: load votes ascending (from={}, limit={}) failed: {}",
                    fromEpoch, limit, e.getMessage());
        }
        return out;
    }

    @Override
    public Set<String> loadFinalizedKeysSince(long fromEpoch) {
        Set<String> out = new LinkedHashSet<>();
        try {
            jdbc.query("SELECT epoch, checkpoint_hash FROM finality_checkpoints WHERE epoch >= ?", rs -> {
                out.add(rs.getLong("epoch") + "|" + rs.getString("checkpoint_hash"));
            }, fromEpoch);
        } catch (RuntimeException e) {
            log.warn("JdbcFinalityStateStore: load finalized since {} failed: {}", fromEpoch, e.getMessage());
        }
        return out;
    }

    @Override
    public void markFinalized(long epoch, byte[] checkpointHash) {
        String ck = hashStr(checkpointHash);
        JdbcUpsert.insertIgnore(jdbc,
                "INSERT INTO finality_checkpoints(epoch, checkpoint_hash) VALUES(?,?)",
                new Object[]{epoch, ck});
        finalizedCache.put(key(epoch, ck), Boolean.TRUE);
    }

    @Override
    public boolean isFinalized(long epoch, byte[] checkpointHash) {
        return Boolean.TRUE.equals(finalizedCache.get(key(epoch, hashStr(checkpointHash))));
    }

    @Override
    public Set<String> loadVoters(long epoch, byte[] checkpointHash) {
        Set<String> v = voteCache.get(key(epoch, hashStr(checkpointHash)));
        return v == null ? Set.of() : Set.copyOf(v);
    }

    @Override
    public Map<String, Boolean> loadAllFinalized() {
        return Map.copyOf(finalizedCache);
    }

    @Override
    public Map<String, Set<String>> loadAllVotes() {
        return Map.copyOf(voteCache);
    }

    private static String hashStr(byte[] bytes) {
        // checkpoint 哈希的规范化字符串表示（与 InMemory/FinalityGadget 的 key 编码一致）。
        // 32 字节哈希编码为 64 个 hex 字符，稳妥落在 VARCHAR(128) 内；
        // 旧实现用 Arrays.toString(byte[])，32 字节可达 160+ 字符，PG 会报
        // "value too long for type character varying(128)"。
        return HexFormat.of().formatHex(bytes == null ? new byte[0] : bytes);
    }

    /** 二进制 → hex 字符串（NULL 安全；空数组视为无值）。 */
    private static String hexOrNull(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return HexFormat.of().formatHex(bytes);
    }

    /** hex 字符串 → 二进制（NULL/非法安全）。 */
    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring malformed hex value in finality store: {}", e.getMessage());
            return null;
        }
    }

    static String key(long epoch, String checkpointHex) {
        return epoch + "|" + checkpointHex;
    }
}