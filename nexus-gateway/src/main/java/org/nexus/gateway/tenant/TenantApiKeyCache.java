package org.nexus.gateway.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 租户 API Key 内存缓存（性能优化任务 #310）。
 *
 * <p>为 {@link TenantApiKeyInterceptor} 的高频鉴权查询提供 TTL 缓存，
 * 避免每次 API 请求都查询数据库。租户数量有限（通常 <1000），
 * 缓存命中率高，可显著降低数据库负载。</p>
 *
 * <h3>设计要点</h3>
 * <ul>
 *   <li><b>TTL 过期</b>：默认 5 分钟，过期后下次查询回源并刷新缓存。
 *       平衡缓存命中率与配置变更的可见性（租户停用/删除后最长 5 分钟生效）。</li>
 *   <li><b>负缓存</b>：不存在的 apiKey 也缓存（值为空 Optional），避免恶意请求打穿缓存。</li>
 *   <li><b>线程安全</b>：使用 {@link ConcurrentHashMap}，无锁读路径。</li>
 *   <li><b>主动失效</b>：{@link #invalidate} 在租户状态变更时调用，立即清除缓存条目。</li>
 * </ul>
 *
 * <h3>性能收益</h3>
 * <p>每个 API 请求节省 1 次数据库查询（findByApiKey）。按 1000 QPS 估算，
 * 每秒减少 1000 次 DB 查询，显著降低数据库连接池压力与 CPU 开销。</p>
 *
 * @since 性能优化任务 #310
 */
public class TenantApiKeyCache {

    private static final Logger log = LoggerFactory.getLogger(TenantApiKeyCache.class);

    /** 默认 TTL：5 分钟。 */
    static final long DEFAULT_TTL_MILLIS = 5 * 60 * 1000L;

    /**
     * 条目数上限（P2，2026-09-21 修复）。
     *
     * <p>此前仅有 TTL、**无容量上限**。TTL 是读时惰性过期：若某 API Key 写入后
     * 再无请求命中，其条目不会被清除；且负缓存（Optional.empty()）同样占位。
     * 长期运行下条目数随「出现过的不同 API Key 数量」单调增长 → 内存无界膨胀。
     * 与 {@code JdbcPaymentStateStore} 的同类缺陷一致。</p>
     *
     * <p>本模块未引入 Guava，故不新增依赖，采用零依赖的近似 LRU 淘汰：
     * 达到上限时先清已过期条目，仍超限则淘汰最旧的一条。</p>
     */
    static final int MAX_ENTRIES = 10_000;

    private final long ttlMillis;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 使用默认 TTL（5 分钟）创建缓存。
     */
    public TenantApiKeyCache() {
        this(DEFAULT_TTL_MILLIS);
    }

    /**
     * 指定 TTL 创建缓存（测试用）。
     *
     * @param ttlMillis 缓存条目存活时间（毫秒）
     */
    public TenantApiKeyCache(long ttlMillis) {
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : DEFAULT_TTL_MILLIS;
    }

    /**
     * 查询缓存。
     *
     * @param apiKey 待查询的 API Key
     * @return 命中且未过期返回 Optional.of(tenant) 或 Optional.empty()（负缓存）；
     *         未命中或已过期返回 null（调用方应回源查询并调用 {@link #put}）
     */
    public Optional<Tenant> get(String apiKey) {
        if (apiKey == null || apiKey.isEmpty()) {
            return Optional.empty();
        }
        CacheEntry entry = cache.get(apiKey);
        if (entry == null) {
            return null; // cache miss
        }
        if (System.currentTimeMillis() - entry.cachedAt > ttlMillis) {
            // 过期：移除并返回 miss
            cache.remove(apiKey, entry);
            return null;
        }
        return entry.tenant;
    }

    /**
     * 写入缓存（含负缓存）。
     *
     * @param apiKey  API Key
     * @param tenant  查询结果（Optional.empty() 表示不存在，负缓存）
     */
    public void put(String apiKey, Optional<Tenant> tenant) {
        if (apiKey == null || apiKey.isEmpty()) {
            return;
        }
        // P2（2026-09-21）：写入前确保不超上限，避免无界增长
        if (cache.size() >= MAX_ENTRIES) {
            evictExpired();
            if (cache.size() >= MAX_ENTRIES) {
                evictOldest();
            }
        }
        cache.put(apiKey, new CacheEntry(tenant, System.currentTimeMillis()));
    }

    /** 清除所有已过期条目。 */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().cachedAt > ttlMillis);
    }

    /** 淘汰最旧的一条（近似 LRU）。 */
    private void evictOldest() {
        cache.entrySet().stream()
                .min(java.util.Comparator.comparingLong(e -> e.getValue().cachedAt))
                .ifPresent(oldest -> {
                    cache.remove(oldest.getKey(), oldest.getValue());
                    log.warn("TenantApiKeyCache at capacity ({}), evicted oldest entry", MAX_ENTRIES);
                });
    }


    /**
     * 主动失效单个 API Key 的缓存。
     *
     * <p>租户状态变更（停用/激活/删除）时调用，确保下次查询回源。</p>
     *
     * @param apiKey 待失效的 API Key
     */
    public void invalidate(String apiKey) {
        if (apiKey != null) {
            cache.remove(apiKey);
        }
    }

    /**
     * 清空全部缓存（测试 / 全量刷新用）。
     */
    public void invalidateAll() {
        cache.clear();
        log.debug("Tenant API key cache fully invalidated");
    }

    /**
     * 获取当前缓存条目数（监控 / 测试用）。
     */
    public int size() {
        return cache.size();
    }

    /** 缓存条目：值 + 写入时间戳。 */
    private static final class CacheEntry {
        final Optional<Tenant> tenant;
        final long cachedAt;

        CacheEntry(Optional<Tenant> tenant, long cachedAt) {
            this.tenant = tenant;
            this.cachedAt = cachedAt;
        }
    }
}