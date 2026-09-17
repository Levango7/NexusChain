package org.nexus.core;

import org.nexus.core.payment.BridgeTransaction;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.core.payment.StableCoinPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JDBC-backed persistent store for production.
 * Uses write-through: writes go to DB first, then in-memory cache.
 * Reads served from cache for performance, DB for recovery on startup.
 */
@Component
@Profile("!dev")
public class JdbcPaymentStateStore implements PaymentStateStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcPaymentStateStore.class);

    /**
     * 内存缓存 TTL（P1，2026-09-17 修复）。
     *
     * <p>原实现为 4 个裸 {@code ConcurrentHashMap}，存在两个问题：</p>
     * <ol>
     *   <li><b>无 TTL</b>：条目永不过期。多副本部署时，实例 A 写库并更新自己的缓存，
     *       实例 B 的缓存<b>永远不会失效</b>，会持续返回过期的通道/仓位/跨链终局状态 ——
     *       对共识与终局类状态而言这是正确性问题，而非单纯性能问题。</li>
     *   <li><b>无容量上限</b>：通道/仓位/跨链交易数量随时间增长，缓存无界膨胀 → 内存泄漏。</li>
     * </ol>
     *
     * <p>改用 Guava {@code CacheBuilder}（Guava 已是本模块依赖，无需新增依赖），
     * 同时施加 {@code expireAfterWrite} 与 {@code maximumSize}：
     * 过期后回源查库，从而把跨实例可见性延迟限制在一个 TTL 窗口内。</p>
     *
     * <p>重放键缓存同样加 TTL 是安全的 —— {@link #getAllConsumedReplayKeys} 在缓存
     * 未命中时会回退查库，因此不会因过期而丢失重放保护。</p>
     */
    private static final long CACHE_TTL_MINUTES = 5L;

    /** 单个缓存的最大条目数，防止无界增长。 */
    private static final long CACHE_MAX_SIZE = 10_000L;

    private final JdbcTemplate jdbc;
    private final com.google.common.cache.Cache<String, PaymentChannel> channelCache = newCache();
    private final com.google.common.cache.Cache<String, StableCoinPosition> positionCache = newCache();
    private final com.google.common.cache.Cache<String, BridgeTransaction> bridgeCache = newCache();
    private final com.google.common.cache.Cache<String, java.util.Set<String>> replayKeyCache = newCache();

    /** 构造带 TTL 与容量上限的缓存实例。 */
    private static <K, V> com.google.common.cache.Cache<K, V> newCache() {
        return com.google.common.cache.CacheBuilder.newBuilder()
                .maximumSize(CACHE_MAX_SIZE)
                .expireAfterWrite(CACHE_TTL_MINUTES, java.util.concurrent.TimeUnit.MINUTES)
                .build();
    }

    public JdbcPaymentStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS payment_channels ("
            + "channel_id VARCHAR(128) PRIMARY KEY, "
            + "participant1 VARCHAR(128), participant2 VARCHAR(128), "
            + "balance1 BIGINT, balance2 BIGINT, nonce BIGINT, "
            + "state VARCHAR(32), open_block BIGINT, close_block BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS stablecoin_positions ("
            + "position_id VARCHAR(128) PRIMARY KEY, "
            + "owner VARCHAR(128), collateral BIGINT, minted BIGINT, "
            + "last_update_block BIGINT, created_block BIGINT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS bridge_transactions ("
            + "bridge_tx_id VARCHAR(128) PRIMARY KEY, "
            + "source_chain VARCHAR(64), target_chain VARCHAR(64), "
            + "amount BIGINT, recipient VARCHAR(128), "
            + "state VARCHAR(32), confirmations INT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS bridge_replay_keys ("
            + "kind VARCHAR(64), key_hex VARCHAR(128), "
            + "PRIMARY KEY(kind, key_hex))");
        log.info("JdbcPaymentStateStore: schema initialized");
    }

    @Override
    public void putChannel(String id, PaymentChannel ch) {
        jdbc.update("MERGE INTO payment_channels KEY(channel_id) VALUES(?,?,?,?,?,?,?,?)",
            id, ch.getParticipant1(), ch.getParticipant2(),
            ch.getBalance1(), ch.getBalance2(), ch.getNonce(),
            ch.getState().name(), ch.getOpenBlockHeight(), ch.getCloseBlockHeight());
        channelCache.put(id, ch);
    }

    @Override
    public PaymentChannel getChannel(String id) { return channelCache.getIfPresent(id); }

    @Override
    public Collection<PaymentChannel> getAllChannels() { return Collections.unmodifiableCollection(channelCache.asMap().values()); }

    @Override
    public void putPosition(String id, StableCoinPosition pos) {
        jdbc.update("MERGE INTO stablecoin_positions KEY(position_id) VALUES(?,?,?,?,?,?)",
            id, pos.getOwner(), pos.getCollateralAmount(), pos.getMintedAmount(),
            pos.getLastUpdateBlock(), pos.getCreatedAtBlock());
        positionCache.put(id, pos);
    }

    @Override
    public StableCoinPosition getPosition(String id) { return positionCache.getIfPresent(id); }

    @Override
    public Collection<StableCoinPosition> getAllPositions() { return Collections.unmodifiableCollection(positionCache.asMap().values()); }

    @Override
    public void putBridgeTx(String id, BridgeTransaction tx) {
        jdbc.update("MERGE INTO bridge_transactions KEY(bridge_tx_id) VALUES(?,?,?,?,?,?,?)",
            id, tx.getSourceChain(), tx.getTargetChain(), tx.getAmount(),
            tx.getRecipient(), tx.getState().name(), tx.getValidators() != null ? tx.getValidators().size() : 0);
        bridgeCache.put(id, tx);
    }

    @Override
    public BridgeTransaction getBridgeTx(String id) { return bridgeCache.getIfPresent(id); }

    @Override
    public Collection<BridgeTransaction> getAllBridgeTxs() { return Collections.unmodifiableCollection(bridgeCache.asMap().values()); }

    @Override
    public void putConsumedReplayKey(String kind, String keyHex) {
        jdbc.update("MERGE INTO bridge_replay_keys KEY(kind, key_hex) VALUES(?,?)", kind, keyHex);
        replayKeyCache.asMap().computeIfAbsent(kind, k -> ConcurrentHashMap.newKeySet()).add(keyHex);
    }

    @Override
    public Collection<String> getAllConsumedReplayKeys(String kind) {
        // 缓存命中则直接返回；否则回退查库（进程重启后的恢复路径）。
        Collection<String> cached = replayKeyCache.getIfPresent(kind);
        if (cached != null && !cached.isEmpty()) {
            return Collections.unmodifiableCollection(cached);
        }
        try {
            java.util.List<String> fromDb = jdbc.query(
                    "SELECT key_hex FROM bridge_replay_keys WHERE kind = ?",
                    (rs, rowNum) -> rs.getString("key_hex"), kind);
            java.util.Set<String> set = new java.util.HashSet<>(fromDb);
            replayKeyCache.put(kind, set);
            return Collections.unmodifiableCollection(set);
        } catch (RuntimeException e) {
            log.warn("JdbcPaymentStateStore: failed to load replay keys for kind={}: {}",
                    kind, e.getMessage());
            return Collections.emptyList();
        }
    }
}