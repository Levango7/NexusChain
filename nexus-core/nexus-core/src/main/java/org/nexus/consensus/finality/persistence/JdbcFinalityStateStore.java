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