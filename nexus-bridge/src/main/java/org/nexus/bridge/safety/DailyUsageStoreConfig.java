package org.nexus.bridge.safety;

import org.nexus.bridge.repository.BridgeDailyUsageRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@link DailyUsageStore} 装配开关（PLAN-038），镜像 {@code MessageStoreConfig}：
 *
 * <ul>
 *   <li>{@code memory}（默认，matchIfMissing）— {@link InMemoryDailyUsageStore}：
 *       与历史行为一致；重启清零、多副本各持一份，仅供测试与单实例开发；</li>
 *   <li>{@code jpa} — {@link JdbcDailyUsageStore}：落库（重启不清零）+
 *       多副本全局原子（行级锁）。生产（重启敏感 / 多副本）应配置 jpa。</li>
 * </ul>
 *
 * <p>开关：{@code nexus.bridge.daily-usage.store}。</p>
 *
 * @since 2.76.0
 */
@Configuration
public class DailyUsageStoreConfig {

    /**
     * 落库实现（nexus.bridge.daily-usage.store=jpa 时生效）。
     */
    @Bean
    @ConditionalOnProperty(name = "nexus.bridge.daily-usage.store", havingValue = "jpa")
    public DailyUsageStore jdbcDailyUsageStore(BridgeDailyUsageRepository repository,
                                               PlatformTransactionManager transactionManager) {
        return new JdbcDailyUsageStore(repository, transactionManager);
    }

    /**
     * 进程内实现（默认，保持既有行为）。
     */
    @Bean
    @ConditionalOnProperty(name = "nexus.bridge.daily-usage.store",
            havingValue = "memory", matchIfMissing = true)
    public DailyUsageStore inMemoryDailyUsageStore() {
        return new InMemoryDailyUsageStore();
    }
}
