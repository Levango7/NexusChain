package org.nexus.gateway.ops;

/**
 * 缓存统计信息 DTO。
 *
 * <p>封装单个缓存的运行时统计指标，包括命中/未命中次数、命中率、加载耗时等。
 * 用于 GET /api/v1/ops/cache/stats 端点响应。</p>
 */
public class CacheStatsDTO {

    /** 缓存名称 */
    private String cacheName;

    /** 缓存大小（当前条目数） */
    private long size;

    /** 缓存命中次数 */
    private long hitCount;

    /** 缓存未命中次数 */
    private long missCount;

    /** 缓存命中率（0.0 ~ 1.0） */
    private double hitRate;

    /** 加载成功次数 */
    private long loadSuccessCount;

    /** 加载失败次数 */
    private long loadFailureCount;

    /** 平均加载耗时（毫秒） */
    private double averageLoadTime;

    /** 缓存驱逐次数 */
    private long evictionCount;

    public CacheStatsDTO() {
    }

    public CacheStatsDTO(String cacheName, long size, long hitCount, long missCount,
                         double hitRate, long loadSuccessCount, long loadFailureCount,
                         double averageLoadTime, long evictionCount) {
        this.cacheName = cacheName;
        this.size = size;
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.hitRate = hitRate;
        this.loadSuccessCount = loadSuccessCount;
        this.loadFailureCount = loadFailureCount;
        this.averageLoadTime = averageLoadTime;
        this.evictionCount = evictionCount;
    }

    // --- Getters and Setters ---

    public String getCacheName() { return cacheName; }
    public void setCacheName(String cacheName) { this.cacheName = cacheName; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public long getHitCount() { return hitCount; }
    public void setHitCount(long hitCount) { this.hitCount = hitCount; }

    public long getMissCount() { return missCount; }
    public void setMissCount(long missCount) { this.missCount = missCount; }

    public double getHitRate() { return hitRate; }
    public void setHitRate(double hitRate) { this.hitRate = hitRate; }

    public long getLoadSuccessCount() { return loadSuccessCount; }
    public void setLoadSuccessCount(long loadSuccessCount) { this.loadSuccessCount = loadSuccessCount; }

    public long getLoadFailureCount() { return loadFailureCount; }
    public void setLoadFailureCount(long loadFailureCount) { this.loadFailureCount = loadFailureCount; }

    public double getAverageLoadTime() { return averageLoadTime; }
    public void setAverageLoadTime(double averageLoadTime) { this.averageLoadTime = averageLoadTime; }

    public long getEvictionCount() { return evictionCount; }
    public void setEvictionCount(long evictionCount) { this.evictionCount = evictionCount; }
}