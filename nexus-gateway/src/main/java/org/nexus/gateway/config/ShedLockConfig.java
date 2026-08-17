package org.nexus.gateway.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock 分布式锁配置（项10：ReconciliationTask 多实例去重）。
 *
 * <h3>背景</h3>
 * <p>{@link org.nexus.gateway.execution.ReconciliationTask} 通过 {@code @Scheduled}
 * 每 5 分钟执行一次对账。在多实例部署（k8s 副本数 &gt; 1）场景下，所有实例的调度器
 * 都会触发该任务，导致：</p>
 * <ul>
 *   <li>重复扫描 PENDING 超时记录，{@code CompensationService} 重复补偿</li>
 *   <li>重复对账 COMPLETED/PENDING 退款，重复 RPC 调用浪费链节点资源</li>
 *   <li>重复生成对账报告，日志/监控指标膨胀</li>
 * </ul>
 *
 * <h3>方案</h3>
 * <p>引入 <a href="https://github.com/lukas-krecan/ShedLock">ShedLock</a> 5.10.0，
 * 使用 JDBC provider 复用 gateway 已有的 MySQL/PostgreSQL 数据源
 * （无需额外引入 Redis 集群）。锁状态持久化到 {@code shedlock} 表
 * （由 V11 Flyway migration 创建），{@code JdbcTemplateLockProvider}
 * 通过原子 SQL 更新保证锁的互斥性。</p>
 *
 * <h3>{@code usingDbTime()} 说明</h3>
 * <p>使用数据库服务器时间（{@code CURRENT_TIMESTAMP}）而非 JVM 时间计算
 * {@code lock_until}，避免多实例 JVM 时钟漂移导致锁提前释放或持有过久。
 * 这要求所有实例与数据库服务器时间偏差在可接受范围内（生产环境通过 NTP 保证）。</p>
 *
 * <h3>启用方式</h3>
 * <p>本配置类提供 {@link LockProvider} Bean，配合
 * {@link org.nexus.gateway.GatewayApplication} 上的 {@code @EnableSchedulerLock}
 * 启用 ShedLock AOP 代理，拦截带 {@code @SchedulerLock} 注解的调度方法。</p>
 *
 * <h3>表结构</h3>
 * <p>{@code shedlock} 表（V11 migration）：</p>
 * <pre>
 * CREATE TABLE shedlock (
 *     name       VARCHAR(64)  NOT NULL,    -- 锁名（@SchedulerLock name 属性）
 *     lock_until TIMESTAMP    NOT NULL,    -- 锁持有截止时间（db time + lockAtMostFor）
 *     locked_at  TIMESTAMP    NOT NULL,    -- 加锁时间（db time）
 *     locked_by  VARCHAR(255) NOT NULL,    -- 持有锁的实例标识（hostname）
 *     PRIMARY KEY (name)                    -- 同名锁全局唯一
 * );
 * </pre>
 */
@Configuration
public class ShedLockConfig {

    /**
     * 构建 JDBC-based {@link LockProvider}。
     *
     * <p>使用 {@link JdbcTemplate} 操作 {@code shedlock} 表，
     * {@code usingDbTime()} 让锁时间计算依赖数据库服务器时间，
     * 规避多实例 JVM 时钟漂移问题。</p>
     *
     * <p>ShedLock 5.10.0 API：通过 {@code JdbcTemplateLockProvider.Configuration.builder()}
     * 构建 Configuration，再传入 {@code JdbcTemplateLockProvider} 构造函数
     * （5.x 移除了旧的 {@code JdbcTemplateLockProvider.builder()} 静态工厂）。</p>
     *
     * @param jdbcTemplate Spring 自动注入的 JdbcTemplate（基于 primary DataSource）
     * @return JdbcTemplateLockProvider 实例
     */
    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        JdbcTemplateLockProvider.Configuration configuration = JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .usingDbTime()
                .build();
        return new JdbcTemplateLockProvider(configuration);
    }
}