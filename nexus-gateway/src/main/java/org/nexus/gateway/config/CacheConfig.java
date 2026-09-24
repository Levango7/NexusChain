package org.nexus.gateway.config;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Cache 基础设施配置。
 *
 * <p>背景：任务 #26（运维管理 API）引入的 {@code OpsService} 通过构造器强制依赖
 * {@link CacheManager} 以提供缓存统计与清理能力，但全仓此前从未启用 Spring Cache
 * 基础设施——既无 {@code @EnableCaching}，也无 {@code CacheManager} bean 定义，
 * 且 Spring Boot 4.0 已将 cache 自动配置拆分为独立模块，现有 starter 未传递引入。</p>
 *
 * <p>后果：{@code opsService} / {@code opsController} 因找不到 {@code CacheManager}
 * bean 而创建失败（{@code NoSuchBeanDefinitionException}），导致应用上下文与全部
 * {@code @SpringBootTest} 集成测试无法启动。</p>
 *
 * <p>本配置显式声明进程内 {@link ConcurrentMapCacheManager}，缓存名与
 * {@code nexus.ops.cache.names} 默认值保持一致。选择内存实现而非 Redis：
 * 运维缓存统计/清理面向单实例运维场景，且可避免测试环境对 Redis 的强依赖；
 * 若后续需多实例共享缓存，可平滑替换为 {@code RedisCacheManager}。</p>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 连接器配置缓存（与 nexus.ops.cache.names 默认值对齐） */
    public static final String CACHE_CONNECTOR_CONFIGS = "connectorConfigs";

    /** 商户限额配置缓存（与 nexus.ops.cache.names 默认值对齐） */
    public static final String CACHE_MERCHANT_LIMITS = "merchantLimits";

    /** 结算配置缓存（与 nexus.ops.cache.names 默认值对齐） */
    public static final String CACHE_SETTLEMENT_CONFIGS = "settlementConfigs";

    /**
     * 进程内缓存管理器。
     *
     * <p>显式指定三个缓存名，使 {@code OpsService.getCacheStats()} 在缓存尚未被
     * 使用时也能列出这些运维关注的缓存（避免 dynamic 模式下 {@code getCacheNames()}
     * 初始为空导致统计缺失）。</p>
     */
    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(
                CACHE_CONNECTOR_CONFIGS, CACHE_MERCHANT_LIMITS, CACHE_SETTLEMENT_CONFIGS);
    }
}