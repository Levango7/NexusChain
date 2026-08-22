package org.nexus.consensus.finality.persistence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC（Postgres/H2）版最终性状态存储。
 *
 * <p>对齐 {@code JdbcPaymentStateStore} 模式：{@link JdbcTemplate} + 建表 + MERGE。
 * 仅在 <b>写入时落库</b>，读取走内存重建缓存（启动时 {@code loadAll*}）。</p>
 *
 * <p>表结构：</p>
 * <ul>
 *   <li>{@code finality_votes(epoch BIGINT, checkpoint_hash VARCHAR(128), validator VARCHAR(128), PRIMARY KEY(epoch, checkpoint_hash, validator))}</li>
 *   <li>{@code finality_checkpoints(epoch BIGINT, checkpoint_hash VARCHAR(128), PRIMARY KEY(epoch, checkpoint_hash))}</li>
 * </ul>
 */
public class JdbcFinalityStateStore implements FinalityStateStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcFinalityStateStore.class);

    private final JdbcTemplate jdbc;

    /** 启动时从库重建的缓存（读路径不触库） */
    private final Map<String, Set<String>> voteCache = new ConcurrentHashMap<>();
    private final Map<String, Boolean> finalizedCache = new ConcurrentHashMap<>();

    public JdbcFinalityStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS finality_votes ("
            + "epoch BIGINT NOT NULL, checkpoint_hash VARCHAR(128) NOT NULL, "
            + "validator VARCHAR(128) NOT NULL, "
            + "PRIMARY KEY (epoch, checkpoint_hash, validator))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS finality_checkpoints ("
            + "epoch BIGINT NOT NULL, checkpoint_hash VARCHAR(128) NOT NULL, "
            + "PRIMARY KEY (epoch, checkpoint_hash))");
        log.info("JdbcFinalityStateStore: schema initialized");
        reload();
    }

    /** 从库重建内存缓存（启动/恢复用）。 */
    private void reload() {
        try {
            jdbc.query("SELECT epoch, checkpoint_hash, validator FROM finality_votes", rs -> {
                while (rs.next()) {
                    String key = rs.getLong("epoch") + "|" + rs.getString("checkpoint_hash");
                    voteCache.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet())
                            .add(rs.getString("validator"));
                }
            });
            jdbc.query("SELECT epoch, checkpoint_hash FROM finality_checkpoints", rs -> {
                while (rs.next()) {
                    finalizedCache.put(rs.getLong("epoch") + "|" + rs.getString("checkpoint_hash"), Boolean.TRUE);
                }
            });
            log.info("JdbcFinalityStateStore: reloaded {} vote records, {} finalized checkpoints",
                    voteCache.size(), finalizedCache.size());
        } catch (RuntimeException e) {
            log.error("JdbcFinalityStateStore.reload failed: {}", e.getMessage());
        }
    }

    @Override
    public void recordVote(long epoch, byte[] checkpointHash, String validatorAddress) {
        String ck = hashStr(checkpointHash);
        jdbc.update("MERGE INTO finality_votes KEY(epoch, checkpoint_hash, validator) VALUES(?,?,?)",
                epoch, ck, validatorAddress);
        voteCache.computeIfAbsent(key(epoch, ck), k -> ConcurrentHashMap.newKeySet()).add(validatorAddress);
    }

    @Override
    public void markFinalized(long epoch, byte[] checkpointHash) {
        String ck = hashStr(checkpointHash);
        jdbc.update("MERGE INTO finality_checkpoints KEY(epoch, checkpoint_hash) VALUES(?,?)",
                epoch, ck);
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
        // checkpoint 哈希的规范化字符串表示（与 InMemory 实现的 key 编码一致）
        return Arrays.toString(bytes == null ? new byte[0] : bytes);
    }

    static String key(long epoch, String checkpointHex) {
        return epoch + "|" + checkpointHex;
    }
}