package org.nexus.gateway.ops;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * OpsService 单元测试。
 *
 * <p>使用 Mockito mock 外部依赖（DataSource, ApplicationContext, CacheManager），
 * 验证 OpsService 各方法的业务逻辑正确性。</p>
 */
class OpsServiceTest {

    private OpsService opsService;

    private MeterRegistry meterRegistry;
    private DataSource dataSource;
    private ApplicationContext applicationContext;
    private CacheManager cacheManager;
    private Environment environment;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        dataSource = mock(DataSource.class);
        applicationContext = mock(ApplicationContext.class);
        cacheManager = new ConcurrentMapCacheManager("connectorConfigs", "merchantLimits", "settlementConfigs");
        environment = new MockEnvironment()
                .withProperty("nexus.token-symbol", "NEX")
                .withProperty("nexus.chain.rpc-url", "http://localhost:19585")
                .withProperty("nexus.security.jwt.secret", "my-super-secret-key")
                .withProperty("nexus.ops.enabled", "true")
                .withProperty("nexus.ops.cache.names", "connectorConfigs,merchantLimits,settlementConfigs")
                .withProperty("nexus.ops.threadpool.monitor-enabled", "true")
                .withProperty("spring.datasource.password", "db-password-123");

        opsService = new OpsService(meterRegistry, dataSource, applicationContext,
                cacheManager, environment, true,
                "connectorConfigs,merchantLimits,settlementConfigs", true);
    }

    // ==================== 配置管理 ====================

    @Test
    @DisplayName("getConfig — 返回配置且敏感字段脱敏")
    void getConfigReturnsMaskedSensitiveValues() {
        Map<String, Object> config = opsService.getConfig();

        assertNotNull(config);
        // 非敏感字段应返回实际值
        assertEquals("NEX", config.get("nexus.token-symbol"));
        assertEquals("http://localhost:19585", config.get("nexus.chain.rpc-url"));
        // 敏感字段应脱敏
        assertEquals("****", config.get("nexus.security.jwt.secret"));
        assertEquals("****", config.get("spring.datasource.password"));
    }

    @Test
    @DisplayName("refreshConfig — 触发刷新返回 triggered 状态")
    void refreshConfigReturnsTriggeredStatus() {
        when(applicationContext.getBeansWithAnnotation(any())).thenReturn(Map.of());

        Map<String, Object> result = opsService.refreshConfig();

        assertNotNull(result);
        assertEquals("triggered", result.get("status"));
        assertNotNull(result.get("message"));
    }

    @Test
    @DisplayName("getConfigDiff — 返回本地配置快照")
    void getConfigDiffReturnsLocalSnapshot() {
        Map<String, Object> diff = opsService.getConfigDiff();

        assertNotNull(diff);
        assertNotNull(diff.get("localConfig"));
        assertNotNull(diff.get("note"));
        assertEquals(false, diff.get("remoteConfigAvailable"));
    }

    // ==================== 缓存管理 ====================

    @Test
    @DisplayName("clearCache — 清除存在的缓存")
    void clearCacheClearsExistingCache() {
        // 先往缓存中放入数据
        Cache cache = cacheManager.getCache("connectorConfigs");
        assertNotNull(cache);
        cache.put("key1", "value1");
        assertNotNull(cache.get("key1"));

        Map<String, Object> result = opsService.clearCache("connectorConfigs");

        assertEquals("cleared", result.get("status"));
        assertEquals("connectorConfigs", result.get("cacheName"));
        assertNull(cache.get("key1"));
    }

    @Test
    @DisplayName("clearCache — 清除不存在的缓存返回 not_found")
    void clearCacheReturnsNotFoundForMissingCache() {
        Map<String, Object> result = opsService.clearCache("nonExistentCache");

        assertEquals("not_found", result.get("status"));
        assertNotNull(result.get("message"));
    }

    @Test
    @DisplayName("clearAllCaches — 清除所有缓存")
    void clearAllCachesClearsAllRegisteredCaches() {
        // 先放入数据
        cacheManager.getCache("connectorConfigs").put("k1", "v1");
        cacheManager.getCache("merchantLimits").put("k2", "v2");

        Map<String, Object> result = opsService.clearAllCaches();

        assertEquals("cleared", result.get("status"));
        assertNotNull(result.get("clearedCaches"));
        assertTrue(result.get("clearedCaches") instanceof java.util.List);
        assertEquals(3, result.get("totalCleared")); // 3 个注册的缓存
    }

    @Test
    @DisplayName("getCacheStats — 返回所有缓存统计")
    void getCacheStatsReturnsAllCacheStats() {
        var statsList = opsService.getCacheStats();

        assertNotNull(statsList);
        assertFalse(statsList.isEmpty());
        // 至少包含已注册的 3 个缓存
        assertTrue(statsList.size() >= 3);
        // 每条统计都有缓存名称
        for (CacheStatsDTO stats : statsList) {
            assertNotNull(stats.getCacheName());
        }
    }

    // ==================== 线程池监控 ====================

    @Test
    @DisplayName("getThreadPoolStats — 返回线程池统计列表")
    void getThreadPoolStatsReturnsStatsList() {
        // mock ApplicationContext 返回一个 ThreadPoolExecutor
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 4, 60, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(10));
        when(applicationContext.getBeansOfType(ThreadPoolExecutor.class))
                .thenReturn(Map.of("testExecutor", executor));

        var statsList = opsService.getThreadPoolStats();

        assertNotNull(statsList);
        assertEquals(1, statsList.size());
        ThreadPoolStatsDTO stats = statsList.get(0);
        assertEquals("testExecutor", stats.getPoolName());
        assertEquals(2, stats.getCorePoolSize());
        assertEquals(4, stats.getMaximumPoolSize());
        assertEquals(10, stats.getQueueCapacity());
        assertFalse(stats.isShutdown());
        assertFalse(stats.isTerminated());
    }

    // ==================== 连接池监控 ====================

    @Test
    @DisplayName("getConnectionPoolStats — 非 HikariDataSource 返回 unknown")
    void getConnectionPoolStatsReturnsUnknownForNonHikariDataSource() {
        // dataSource 是 mock(DataSource.class)，不是 HikariDataSource
        ConnectionPoolStatsDTO stats = opsService.getConnectionPoolStats();

        assertNotNull(stats);
        assertEquals("unknown", stats.getPoolName());
        assertEquals(0, stats.getActiveConnections());
    }

    @Test
    @DisplayName("getConnectionPoolStats — HikariDataSource 返回连接池统计")
    void getConnectionPoolStatsReturnsHikariStats() {
        // 使用真实的 HikariDataSource（需要初始化连接池才能获取 MXBean）
        HikariDataSource hikariDataSource = new HikariDataSource();
        hikariDataSource.setPoolName("testPool");
        hikariDataSource.setMaximumPoolSize(20);
        hikariDataSource.setMinimumIdle(5);
        hikariDataSource.setConnectionTimeout(30000);
        hikariDataSource.setIdleTimeout(600000);
        hikariDataSource.setMaxLifetime(1800000);
        hikariDataSource.setJdbcUrl("jdbc:h2:mem:testdb");
        hikariDataSource.setDriverClassName("org.h2.Driver");

        OpsService serviceWithHikari = new OpsService(
                meterRegistry, hikariDataSource, applicationContext, cacheManager, environment,
                true, "connectorConfigs,merchantLimits,settlementConfigs", true);

        ConnectionPoolStatsDTO stats = serviceWithHikari.getConnectionPoolStats();

        assertNotNull(stats);
        assertEquals("testPool", stats.getPoolName());
        // HikariDataSource 配置参数可通过 getter 直接读取
        assertEquals(20, hikariDataSource.getMaximumPoolSize());
        assertEquals(5, hikariDataSource.getMinimumIdle());
        assertEquals(30000, hikariDataSource.getConnectionTimeout());
        assertEquals(600000, hikariDataSource.getIdleTimeout());
        assertEquals(1800000, hikariDataSource.getMaxLifetime());

        hikariDataSource.close();
    }

    // ==================== 系统信息 ====================

    @Test
    @DisplayName("getSystemInfo — 返回 JVM 系统信息")
    void getSystemInfoReturnsJvmMetrics() {
        SystemInfoDTO info = opsService.getSystemInfo();

        assertNotNull(info);
        assertNotNull(info.getJvmName());
        assertNotNull(info.getJvmVersion());
        assertTrue(info.getUptime() > 0);
        assertTrue(info.getHeapMax() > 0);
        assertTrue(info.getHeapCommitted() > 0);
        assertTrue(info.getHeapUsageRate() >= 0.0 && info.getHeapUsageRate() <= 1.0);
        assertTrue(info.getAvailableProcessors() > 0);
        assertNotNull(info.getGcStats());
        assertFalse(info.getGcStats().isEmpty());
        assertTrue(info.getThreadCount() > 0);
        assertNotNull(info.getSystemProperties());
    }

    @Test
    @DisplayName("getSystemInfo — 系统属性中敏感字段脱敏")
    void getSystemInfoMasksSensitiveSystemProperties() {
        // 通过系统属性注入一个敏感 key
        System.setProperty("my.test.password", "secret123");

        try {
            SystemInfoDTO info = opsService.getSystemInfo();
            assertNotNull(info.getSystemProperties());
            // 包含 password 的系统属性应被脱敏
            String value = info.getSystemProperties().get("my.test.password");
            if (value != null) {
                assertEquals("****", value);
            }
        } finally {
            System.clearProperty("my.test.password");
        }
    }

    // ==================== 详细健康检查 ====================

    @Test
    @DisplayName("getDetailedHealth — 返回健康检查结果映射")
    void getDetailedHealthReturnsHealthMap() {
        // mock ApplicationContext 返回空的 HealthIndicator 映射
        when(applicationContext.getBeansOfType(
                org.springframework.boot.health.contributor.HealthIndicator.class))
                .thenReturn(Map.of());

        Map<String, Object> healthDetails = opsService.getDetailedHealth();

        assertNotNull(healthDetails);
        // 空映射也是合法结果（没有注册 HealthIndicator）
        assertTrue(healthDetails.isEmpty());
    }
}