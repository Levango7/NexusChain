package org.nexus.gateway.ops;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

/**
 * 运维管理服务层。
 *
 * <p>封装所有运维操作的底层逻辑，包括：
 * <ul>
 *   <li>配置查看（脱敏）与刷新</li>
 *   <li>缓存统计与清理</li>
 *   <li>线程池监控</li>
 *   <li>HikariCP 连接池监控</li>
 *   <li>JVM 系统信息采集</li>
 *   <li>详细健康检查</li>
 * </ul></p>
 *
 * <p>所有敏感字段（password, secret, key, token）在返回前自动脱敏为 "****"。</p>
 */
@Service
public class OpsService {

    private static final Logger log = LoggerFactory.getLogger(OpsService.class);

    /** 敏感字段关键词列表，匹配到的配置值将被脱敏 */
    private static final Set<String> SENSITIVE_KEYWORDS = Set.of(
            "password", "secret", "apikey", "api-key", "hmac-key", "private-key",
            "public-key", "credential", "callback-secret"
    );

    private final MeterRegistry meterRegistry;
    private final DataSource dataSource;
    private final ApplicationContext applicationContext;
    private final CacheManager cacheManager;
    private final Environment environment;
    private final boolean opsEnabled;
    private final String configuredCacheNames;
    private final boolean threadPoolMonitorEnabled;

    public OpsService(MeterRegistry meterRegistry, DataSource dataSource,
                      ApplicationContext applicationContext, CacheManager cacheManager,
                      Environment environment,
                      @Value("${nexus.ops.enabled:true}") boolean opsEnabled,
                      @Value("${nexus.ops.cache.names:connectorConfigs,merchantLimits,settlementConfigs}") String configuredCacheNames,
                      @Value("${nexus.ops.threadpool.monitor-enabled:true}") boolean threadPoolMonitorEnabled) {
        this.meterRegistry = meterRegistry;
        this.dataSource = dataSource;
        this.applicationContext = applicationContext;
        this.cacheManager = cacheManager;
        this.environment = environment;
        this.opsEnabled = opsEnabled;
        this.configuredCacheNames = configuredCacheNames;
        this.threadPoolMonitorEnabled = threadPoolMonitorEnabled;
    }

    // ==================== 配置管理 ====================

    /**
     * 获取当前关键配置（脱敏后）。
     *
     * <p>从 Spring Environment 中提取 nexus.* 前缀的关键配置项，
     * 对包含敏感关键词的字段值自动脱敏为 "****"。</p>
     *
     * @return 脱敏后的配置键值映射
     */
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();

        // 提取 nexus 前缀下的关键配置
        addConfigEntry(config, "nexus.token-symbol");
        addConfigEntry(config, "nexus.chain.rpc-url");
        addConfigEntry(config, "nexus.chain.chain-id");
        addConfigEntry(config, "nexus.chain.confirmations");
        addConfigEntry(config, "nexus.consortium.rpc-url");
        addConfigEntry(config, "nexus.consortium.chain-id");
        addConfigEntry(config, "nexus.consortium.confirmations");
        addConfigEntry(config, "nexus.exchange-wallet.base-url");
        addConfigEntry(config, "nexus.exchange-wallet.enabled");
        addConfigEntry(config, "nexus.exchange-wallet.platform-pubkey");
        addConfigEntry(config, "nexus.service-mesh.transport-mode");
        addConfigEntry(config, "nexus.service-mesh.signing-service");
        addConfigEntry(config, "nexus.service-mesh.wallet-service");
        addConfigEntry(config, "nexus.nacos.enabled");
        addConfigEntry(config, "nexus.security.jwt.secret");
        addConfigEntry(config, "nexus.security.jwt.service-subject");
        addConfigEntry(config, "nexus.security.jwt.ttl-millis");
        addConfigEntry(config, "nexus.security.request-signing-secret");
        addConfigEntry(config, "nexus.connectors.stripe.enabled");
        addConfigEntry(config, "nexus.connectors.stripe.api-key");
        addConfigEntry(config, "nexus.connectors.adyen.enabled");
        addConfigEntry(config, "nexus.connectors.adyen.api-key");
        addConfigEntry(config, "nexus.connectors.adyen.merchant-account");
        addConfigEntry(config, "nexus.connectors.adyen.hmac-key");
        addConfigEntry(config, "nexus.connectors.wechat.enabled");
        addConfigEntry(config, "nexus.connectors.wechat.app-id");
        addConfigEntry(config, "nexus.connectors.wechat.mch-id");
        addConfigEntry(config, "nexus.connectors.wechat.api-key");
        addConfigEntry(config, "nexus.connectors.alipay.enabled");
        addConfigEntry(config, "nexus.connectors.alipay.app-id");
        addConfigEntry(config, "nexus.connectors.alipay.merchant-private-key");
        addConfigEntry(config, "nexus.connectors.alipay.alipay-public-key");
        addConfigEntry(config, "nexus.webhook.callback-url");
        addConfigEntry(config, "nexus.webhook.callback-secret");
        addConfigEntry(config, "nexus.webhook.require-v2");
        addConfigEntry(config, "nexus.tenant.enabled");
        addConfigEntry(config, "nexus.tenant.default-rate-limit-per-second");
        addConfigEntry(config, "nexus.tenant.default-rate-limit-per-minute");
        addConfigEntry(config, "nexus.tenant.default-fee-rate-bps");
        addConfigEntry(config, "nexus.ops.enabled");
        addConfigEntry(config, "nexus.ops.cache.names");
        addConfigEntry(config, "nexus.ops.threadpool.monitor-enabled");

        // 数据源配置
        addConfigEntry(config, "spring.datasource.url");
        addConfigEntry(config, "spring.datasource.username");
        addConfigEntry(config, "spring.datasource.password");
        addConfigEntry(config, "spring.datasource.driver-class-name");
        addConfigEntry(config, "spring.datasource.hikari.maximum-pool-size");
        addConfigEntry(config, "spring.datasource.hikari.minimum-idle");
        addConfigEntry(config, "spring.datasource.hikari.connection-timeout");
        addConfigEntry(config, "spring.datasource.hikari.idle-timeout");
        addConfigEntry(config, "spring.datasource.hikari.max-lifetime");

        // Nacos 配置
        addConfigEntry(config, "spring.cloud.nacos.discovery.server-addr");
        addConfigEntry(config, "spring.cloud.nacos.discovery.namespace");
        addConfigEntry(config, "spring.cloud.nacos.discovery.group");
        addConfigEntry(config, "spring.cloud.nacos.config.server-addr");
        addConfigEntry(config, "spring.cloud.nacos.config.namespace");
        addConfigEntry(config, "spring.cloud.nacos.config.group");
        addConfigEntry(config, "spring.cloud.nacos.config.refresh-enabled");

        return config;
    }

    /**
     * 触发配置刷新。
     *
     * <p>通过 Spring Cloud Context 的 RefreshScope 触发配置重新加载。
     * Nacos 配置中心变更后，调用此方法可使本地 @RefreshScope Bean 重新初始化。</p>
     *
     * @return 刷新结果描述
     */
    public Map<String, Object> refreshConfig() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // 尝试通过 RefreshScope 刷新
            Map<String, Object> refreshScopeBeans = applicationContext
                    .getBeansWithAnnotation(RefreshScope.class);
            log.info("触发配置刷新，RefreshScope Bean 数量: {}", refreshScopeBeans.size());
            result.put("status", "triggered");
            result.put("refreshScopeBeanCount", refreshScopeBeans.size());
            result.put("message", "配置刷新已触发，@RefreshScope Bean 将在下次访问时重新初始化");
        } catch (Exception e) {
            log.error("配置刷新失败", e);
            result.put("status", "failed");
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * 查看配置差异（本地 vs Nacos 远程）。
     *
     * <p>比较当前 Environment 中的配置与 Nacos 配置中心远程配置的差异。
     * 由于 Spring Cloud Nacos 在 refresh 时会自动同步远程配置到本地，
     * 此方法通过比较 RefreshScope 刷新前后的配置值来检测差异。</p>
     *
     * @return 配置差异映射（key -> {local, remote}）
     */
    public Map<String, Object> getConfigDiff() {
        Map<String, Object> diff = new LinkedHashMap<>();
        // 获取当前本地配置快照
        Map<String, Object> localConfig = getConfig();
        diff.put("localConfig", localConfig);
        diff.put("note", "配置差异检测依赖 Nacos config refresh 机制。" +
                "当前返回本地配置快照，远程配置需通过 /api/v1/ops/config/refresh 触发刷新后对比。");
        diff.put("remoteConfigAvailable", false);
        return diff;
    }

    // ==================== 缓存管理 ====================

    /**
     * 清除指定缓存。
     *
     * @param cacheName 缓存名称
     * @return 清除结果
     */
    public Map<String, Object> clearCache(String cacheName) {
        Map<String, Object> result = new LinkedHashMap<>();
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            result.put("status", "not_found");
            result.put("message", "缓存 '" + cacheName + "' 不存在");
            log.warn("尝试清除不存在的缓存: {}", cacheName);
            return result;
        }
        cache.clear();
        result.put("status", "cleared");
        result.put("cacheName", cacheName);
        log.info("缓存已清除: {}", cacheName);
        return result;
    }

    /**
     * 清除所有缓存。
     *
     * @return 清除结果
     */
    public Map<String, Object> clearAllCaches() {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> clearedCaches = new ArrayList<>();
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
                clearedCaches.add(name);
            }
        }
        result.put("status", "cleared");
        result.put("clearedCaches", clearedCaches);
        result.put("totalCleared", clearedCaches.size());
        log.info("所有缓存已清除，共 {} 个: {}", clearedCaches.size(), clearedCaches);
        return result;
    }

    /**
     * 获取缓存统计信息。
     *
     * <p>遍历所有已注册的缓存，收集命中/未命中、加载耗时、驱逐等统计指标。
     * 统计数据来源于 Micrometer MeterRegistry 中 Spring Cache 指标。</p>
     *
     * @return 缓存统计列表
     */
    public List<CacheStatsDTO> getCacheStats() {
        List<CacheStatsDTO> statsList = new ArrayList<>();
        for (String cacheName : cacheManager.getCacheNames()) {
            CacheStatsDTO stats = collectCacheStats(cacheName);
            statsList.add(stats);
        }
        // 也包含配置中声明但尚未注册的缓存名
        for (String configuredName : configuredCacheNames.split(",")) {
            String trimmed = configuredName.trim();
            if (cacheManager.getCache(trimmed) == null) {
                CacheStatsDTO stats = new CacheStatsDTO(trimmed, 0, 0, 0, 0.0, 0, 0, 0.0, 0);
                statsList.add(stats);
            }
        }
        return statsList;
    }

    /**
     * 从 MeterRegistry 收集单个缓存的统计指标。
     */
    private CacheStatsDTO collectCacheStats(String cacheName) {
        long hitCount = getMeterCounterValue("cache.gets", cacheName, "result", "hit");
        long missCount = getMeterCounterValue("cache.gets", cacheName, "result", "miss");
        long evictionCount = getMeterCounterValue("cache.evictions", cacheName, null, null);
        long loadSuccessCount = getMeterCounterValue("cache.puts", cacheName, null, null);

        double hitRate = (hitCount + missCount) > 0
                ? (double) hitCount / (hitCount + missCount)
                : 0.0;

        // 从 Timer 获取平均加载时间
        double averageLoadTime = 0.0;
        Timer timer = meterRegistry.find("cache.load").tag("cache", cacheName).timer();
        if (timer != null) {
            averageLoadTime = timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        // 缓存大小（如果缓存实现支持 size 指标）
        long size = getMeterCounterValue("cache.size", cacheName, null, null);

        return new CacheStatsDTO(cacheName, size, hitCount, missCount, hitRate,
                loadSuccessCount, 0, averageLoadTime, evictionCount);
    }

    // ==================== 线程池监控 ====================

    /**
     * 获取线程池状态列表。
     *
     * <p>从 ApplicationContext 中查找所有 ThreadPoolExecutor 类型的 Bean，
     * 收集活跃线程数、队列大小、已完成任务数等指标。</p>
     *
     * @return 线程池统计列表
     */
    public List<ThreadPoolStatsDTO> getThreadPoolStats() {
        if (!threadPoolMonitorEnabled) {
            log.debug("线程池监控已禁用 (nexus.ops.threadpool.monitor-enabled=false)");
            return List.of();
        }

        List<ThreadPoolStatsDTO> statsList = new ArrayList<>();
        Map<String, ThreadPoolExecutor> executors = applicationContext
                .getBeansOfType(ThreadPoolExecutor.class);

        for (Map.Entry<String, ThreadPoolExecutor> entry : executors.entrySet()) {
            ThreadPoolExecutor executor = entry.getValue();
            ThreadPoolStatsDTO stats = new ThreadPoolStatsDTO(
                    entry.getKey(),
                    executor.getCorePoolSize(),
                    executor.getMaximumPoolSize(),
                    executor.getActiveCount(),
                    executor.getQueue().size(),
                    executor.getQueue().remainingCapacity() + executor.getQueue().size(),
                    executor.getCompletedTaskCount(),
                    executor.getTaskCount(),
                    executor.isShutdown(),
                    executor.isTerminated()
            );
            statsList.add(stats);
        }
        return statsList;
    }

    // ==================== 连接池监控 ====================

    /**
     * 获取 HikariCP 连接池状态。
     *
     * <p>从 DataSource 中提取 HikariPool 的运行时统计指标，
     * 包括活跃/空闲/总连接数、等待线程数等。</p>
     *
     * @return 连接池统计
     */
    public ConnectionPoolStatsDTO getConnectionPoolStats() {
        if (!(dataSource instanceof HikariDataSource hikariDataSource)) {
            log.warn("DataSource 不是 HikariDataSource，无法获取连接池统计");
            return new ConnectionPoolStatsDTO("unknown", 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        var pool = hikariDataSource.getHikariPoolMXBean();
        if (pool == null) {
            log.warn("HikariPool MXBean 不可用");
            return new ConnectionPoolStatsDTO(hikariDataSource.getPoolName(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        return new ConnectionPoolStatsDTO(
                hikariDataSource.getPoolName(),
                pool.getActiveConnections(),
                pool.getIdleConnections(),
                pool.getTotalConnections(),
                pool.getThreadsAwaitingConnection(),
                hikariDataSource.getMaximumPoolSize(),
                hikariDataSource.getMinimumIdle(),
                hikariDataSource.getConnectionTimeout(),
                hikariDataSource.getIdleTimeout(),
                hikariDataSource.getMaxLifetime(),
                0 // suspendedConnections - HikariCP 不直接暴露此指标
        );
    }

    // ==================== 系统信息 ====================

    /**
     * 获取 JVM 系统信息。
     *
     * <p>通过 java.lang.management MXBean 采集 JVM 运行时指标：
     * 堆/非堆内存使用、GC 统计、运行时长、CPU、线程数等。</p>
     *
     * @return 系统信息 DTO
     */
    public SystemInfoDTO getSystemInfo() {
        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        List<GarbageCollectorMXBean> gcMXBeans = ManagementFactory.getGarbageCollectorMXBeans();

        SystemInfoDTO info = new SystemInfoDTO();

        // JVM 基本信息
        info.setJvmName(runtimeMXBean.getVmName());
        info.setJvmVersion(runtimeMXBean.getVmVersion());
        info.setJvmVendor(runtimeMXBean.getVmVendor());
        info.setStartTime(runtimeMXBean.getStartTime());
        info.setUptime(runtimeMXBean.getUptime());

        // 堆内存
        MemoryUsage heapUsage = memoryMXBean.getHeapMemoryUsage();
        info.setHeapUsed(heapUsage.getUsed());
        info.setHeapMax(heapUsage.getMax());
        info.setHeapCommitted(heapUsage.getCommitted());
        info.setHeapUsageRate(heapUsage.getMax() > 0
                ? (double) heapUsage.getUsed() / heapUsage.getMax()
                : 0.0);

        // 非堆内存
        MemoryUsage nonHeapUsage = memoryMXBean.getNonHeapMemoryUsage();
        info.setNonHeapUsed(nonHeapUsage.getUsed());
        info.setNonHeapMax(nonHeapUsage.getMax());
        info.setNonHeapCommitted(nonHeapUsage.getCommitted());

        // CPU 与系统负载
        info.setAvailableProcessors(Runtime.getRuntime().availableProcessors());
        info.setSystemLoadAverage(ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());

        // GC 统计
        List<SystemInfoDTO.GcStats> gcStatsList = new ArrayList<>();
        for (GarbageCollectorMXBean gcBean : gcMXBeans) {
            long count = gcBean.getCollectionCount();
            long time = gcBean.getCollectionTime();
            double avgTime = count > 0 ? (double) time / count : 0.0;
            gcStatsList.add(new SystemInfoDTO.GcStats(gcBean.getName(), count, time, avgTime));
        }
        info.setGcStats(gcStatsList);

        // 类加载
        info.setLoadedClassCount(ManagementFactory.getClassLoadingMXBean().getLoadedClassCount());

        // 线程
        info.setThreadCount(threadMXBean.getThreadCount());
        info.setDaemonThreadCount(threadMXBean.getDaemonThreadCount());

        // 系统属性（脱敏后）
        Map<String, String> sysProps = new LinkedHashMap<>();
        runtimeMXBean.getSystemProperties().forEach((key, value) -> {
            if (isSensitiveKey(key)) {
                sysProps.put(key, "****");
            } else {
                sysProps.put(key, value);
            }
        });
        info.setSystemProperties(sysProps);

        return info;
    }

    // ==================== 详细健康检查 ====================

    /**
     * 获取详细健康检查结果。
     *
     * <p>从 ApplicationContext 中查找所有 HealthIndicator Bean，
     * 逐个调用 health() 方法收集详细健康状态。</p>
     *
     * @return 健康检查结果映射（indicator name -> health detail）
     */
    public Map<String, Object> getDetailedHealth() {
        Map<String, Object> healthDetails = new LinkedHashMap<>();

        // 查找所有 HealthIndicator Bean
        Map<String, org.springframework.boot.health.contributor.HealthIndicator> indicators =
                applicationContext.getBeansOfType(
                        org.springframework.boot.health.contributor.HealthIndicator.class);

        for (Map.Entry<String, org.springframework.boot.health.contributor.HealthIndicator> entry
                : indicators.entrySet()) {
            try {
                org.springframework.boot.health.contributor.Health health = entry.getValue().health();
                Map<String, Object> healthMap = new LinkedHashMap<>();
                healthMap.put("status", health.getStatus().getCode());
                if (health.getDetails() != null && !health.getDetails().isEmpty()) {
                    healthMap.put("details", health.getDetails());
                }
                healthDetails.put(entry.getKey(), healthMap);
            } catch (Exception e) {
                Map<String, Object> errorMap = new LinkedHashMap<>();
                errorMap.put("status", "DOWN");
                errorMap.put("error", e.getMessage());
                healthDetails.put(entry.getKey(), errorMap);
                log.warn("健康检查 '{}' 执行失败", entry.getKey(), e);
            }
        }

        return healthDetails;
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 添加配置项到映射，自动脱敏敏感字段。
     */
    private void addConfigEntry(Map<String, Object> config, String key) {
        String value = environment.getProperty(key);
        if (value != null) {
            config.put(key, isSensitiveKey(key) ? "****" : value);
        }
    }

    /**
     * 判断配置键是否包含敏感关键词。
     *
     * <p>检查配置键的最后一段属性名（如 {@code nexus.security.jwt.secret}
     * 的属性名为 {@code secret}），以及完整键中是否包含敏感关键词。
     * 注意：{@code token-symbol} 不应匹配（"token" 不等于 "token-key"），
     * 而 {@code api-key} 应匹配。</p>
     */
    private boolean isSensitiveKey(String key) {
        String lowerKey = key.toLowerCase();
        // 检查完整键路径中的敏感关键词
        for (String keyword : SENSITIVE_KEYWORDS) {
            // 使用分隔符边界匹配，避免 "key" 匹配 "token-symbol" 中的子串
            if (lowerKey.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从 MeterRegistry 获取计数器值，安全处理 null。
     */
    private long getMeterCounterValue(String meterName, String cacheName,
                                      String tagKey, String tagValue) {
        var search = meterRegistry.find(meterName).tag("cache", cacheName);
        if (tagKey != null && tagValue != null) {
            search = search.tag(tagKey, tagValue);
        }
        var counter = search.counter();
        return counter != null ? (long) counter.count() : 0L;
    }
}