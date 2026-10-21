package org.nexus.core.pending;

import jakarta.annotation.PostConstruct;
import org.apache.commons.codec.binary.Hex;
import org.nexus.core.Block;
import org.nexus.db.SchemaMeta;
import org.nexus.encoding.CoreTypesDecoder;
import org.nexus.encoding.CoreTypesEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC（PostgreSQL / H2）版待补写区块 outbox（PLAN-019）。
 *
 * <p>表结构（additive-only，幂等 DDL）：</p>
 * <pre>
 * pending_block_outbox(
 *   block_hash  VARCHAR(128) NOT NULL PRIMARY KEY,  -- 区块哈希 hex
 *   height      BIGINT NOT NULL,                     -- 区块高度
 *   payload     TEXT NOT NULL,                       -- 区块 JSON 载荷（codec.encodeBlock）
 *   created_at  BIGINT NOT NULL,
 *   attempts    INT NOT NULL DEFAULT 0,
 *   last_error  VARCHAR(512))
 * </pre>
 *
 * <p>载荷用 {@code TEXT} 存 JSON（与 {@code JdbcFraudProofStateStore} 的批次 JSON 同款）：
 * 编码器 {@code JSONEncodeDecoder} 产出的就是 JSON 文本，转字符串即可，
 * 不必再 base64/hex 一层。</p>
 *
 * <p>未装配 {@link JdbcTemplate}（dev profile / 无库）时**禁用**，
 * 调用方退化为纯内存重试队列（PLAN-017 第一切片的行为）。</p>
 */
@Component
public class JdbcPendingBlockOutbox implements PendingBlockOutbox {

    private static final Logger log = LoggerFactory.getLogger(JdbcPendingBlockOutbox.class);

    /** schema 版本（additive-only 首版，版本号 1）。 */
    public static final int SCHEMA_VERSION = 1;

    /** 版本登记用 store 标识。 */
    static final String STORE_NAME = "pending_block_outbox";

    private final JdbcTemplate jdbc;
    private final CoreTypesEncoder encoder;
    private final CoreTypesDecoder decoder;

    public JdbcPendingBlockOutbox(@Autowired(required = false) JdbcTemplate jdbc,
                                   @Autowired(required = false) CoreTypesEncoder encoder,
                                   @Autowired(required = false) CoreTypesDecoder decoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.decoder = decoder;
    }

    @PostConstruct
    public void init() {
        if (jdbc == null || encoder == null || decoder == null) {
            log.info("JdbcPendingBlockOutbox disabled (no jdbc/encoder/decoder)");
            return;
        }
        try {
            SchemaMeta.ensure(jdbc, STORE_NAME, SCHEMA_VERSION);
            jdbc.execute("CREATE TABLE IF NOT EXISTS pending_block_outbox ("
                    + "block_hash VARCHAR(128) NOT NULL PRIMARY KEY, "
                    + "height BIGINT NOT NULL, "
                    + "payload TEXT NOT NULL, "
                    + "created_at BIGINT NOT NULL, "
                    + "attempts INT NOT NULL DEFAULT 0, "
                    + "last_error VARCHAR(512))");
            log.info("JdbcPendingBlockOutbox: schema initialized");
        } catch (RuntimeException e) {
            // 建表失败不能拖垮节点启动：outbox 是增强能力，缺失时退化为内存重试队列。
            log.warn("JdbcPendingBlockOutbox: schema init failed, block outbox disabled: {}", e.getMessage());
        }
    }

    @Override
    public void enqueue(Block block) {
        if (!enabled() || block == null) {
            return;
        }
        try {
            String hash = Hex.encodeHexString(block.getHash());
            String payload = new String(encoder.encodeBlock(block), StandardCharsets.UTF_8);
            // 幂等 upsert：同一区块重复入队时刷新载荷与尝试次数，不产生重复行。
            int updated = jdbc.update("UPDATE pending_block_outbox SET payload=?, height=?, "
                            + "attempts=attempts+1 WHERE block_hash=?",
                    payload, block.nHeight, hash);
            if (updated == 0) {
                jdbc.update("INSERT INTO pending_block_outbox(block_hash, height, payload, created_at, attempts) "
                                + "VALUES(?,?,?,?,0) ON CONFLICT DO NOTHING",
                        hash, block.nHeight, payload, System.currentTimeMillis());
            }
        } catch (RuntimeException e) {
            // 数据库整体不可用时写不进去：内存队列兜底（调用方负责）。
            log.warn("JdbcPendingBlockOutbox: enqueue failed for height {}: {}",
                    block.nHeight, e.getMessage());
        }
    }

    @Override
    public void remove(Block block) {
        if (!enabled() || block == null) {
            return;
        }
        try {
            jdbc.update("DELETE FROM pending_block_outbox WHERE block_hash=?",
                    Hex.encodeHexString(block.getHash()));
        } catch (RuntimeException e) {
            log.warn("JdbcPendingBlockOutbox: remove failed for height {}: {}",
                    block.nHeight, e.getMessage());
        }
    }

    @Override
    public List<Block> loadPending(int limit) {
        if (!enabled()) {
            return List.of();
        }
        int cap = limit <= 0 ? 32 : limit;
        List<Block> out = new ArrayList<>();
        try {
            jdbc.query("SELECT payload FROM pending_block_outbox ORDER BY height ASC LIMIT " + cap, rs -> {
                try {
                    byte[] raw = rs.getString("payload").getBytes(StandardCharsets.UTF_8);
                    Block block = decoder.decodeBlock(raw);
                    if (block != null) {
                        out.add(block);
                    }
                } catch (RuntimeException e) {
                    // 单条载荷解码失败不能影响其它行。
                    log.warn("JdbcPendingBlockOutbox: skip undecodable payload: {}", e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("JdbcPendingBlockOutbox: load pending failed: {}", e.getMessage());
        }
        return out;
    }

    @Override
    public void markFailure(Block block, String error) {
        if (!enabled() || block == null) {
            return;
        }
        try {
            jdbc.update("UPDATE pending_block_outbox SET attempts=attempts+1, last_error=? WHERE block_hash=?",
                    error == null ? "" : (error.length() > 500 ? error.substring(0, 500) : error),
                    Hex.encodeHexString(block.getHash()));
        } catch (RuntimeException e) {
            // 失败计数只是观测信息，忽略。
        }
    }

    @Override
    public int pendingCount() {
        if (!enabled()) {
            return -1;
        }
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pending_block_outbox", Integer.class);
            return count == null ? -1 : count;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private boolean enabled() {
        return jdbc != null && encoder != null && decoder != null;
    }
}
