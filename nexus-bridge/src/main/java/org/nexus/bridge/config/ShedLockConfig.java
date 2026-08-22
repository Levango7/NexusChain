package org.nexus.bridge.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.PostConstruct;

/**
 * ShedLock 分布式锁配置（中6 改进：BridgeSagaCoordinator 多实例去重）。
 *
 * <h3>背景</h3>
 * <p>{@link org.nexus.bridge.saga.BridgeSagaCoordinator#recoverIncompleteSagas()}
 * 与 {@link org.nexus.bridge.saga.BridgeSagaCoordinator#retryFailedSagas()}
 * 在多实例部署（k8s 副本数 &gt; 1）场景下，所有实例都会触发，
 * 导致重复扫描 Saga 记录、重复恢复/重试、重复链上调用。</p>
 *
 * <h3>方案</h3>
 * <p>引入 <a href="https://github.com/lukas-krecan/ShedLock">ShedLock</a> 5.10.0，
 * 使用 JDBC provider 复用 bridge 已有的 MySQL 数据源
 * （无需额外引入 Redis 集群）。锁状态持久化到 {@code shedlock} 表，
 * {@code JdbcTemplateLockProvider} 通过原子 SQL 更新保证锁的互斥性。</p>
 *
 * <h3>{@code usingDbTime()} 说明</h3>
 * <p>使用数据库服务器时间（{@code CURRENT_TIMESTAMP}）而非 JVM 时间计算
 * {@code lock_until}，避免多实例 JVM 时钟漂移导致锁提前释放或持有过久。</p>
 *
 * <h3>启用方式</h3>
 * <p>本配置类提供 {@link LockProvider} Bean，配合
 * {@link org.nexus.bridge.BridgeApplication} 上的 {@code @EnableSchedulerLock}
 * 启用 ShedLock AOP 代理，拦截带 {@code @SchedulerLock} 注解的调度方法。</p>
 *
 * <h3>表结构</h3>
 * <p>bridge 不使用 Flyway（{@code spring.jpa.hibernate.ddl-auto=update}），
 * 因此 {@code shedlock} 表通过 {@code @PostConstruct} 自动创建
 * （{@code CREATE TABLE IF NOT EXISTS}，幂等安全）：</p>
 * <pre>
 * CREATE TABLE IF NOT EXISTS shedlock (
 *     name       VARCHAR(64)  NOT NULL,    -- 锁名（@SchedulerLock name 属性）
 *     lock_until TIMESTAMP    NOT NULL,    -- 锁持有截止时间（db time + lockAtMostFor）
 *     locked_at  TIMESTAMP    NOT NULL,    -- 加锁时间（db time）
 *     locked_by  VARCHAR(255) NOT NULL,    -- 持有锁的实例标识（hostname）
 *     PRIMARY KEY (name)                    -- 同名锁全局唯一
 * );
 * </pre>
 *
 * @since 2.2.0
 */
@Configuration
public class ShedLockConfig {

    private static final Logger log = LoggerFactory.getLogger(ShedLockConfig.class);

    /** shedlock 建表 SQL（与 gateway V11__shedlock.sql 对齐，MySQL 语法）。 */
    private static final String CREATE_SHEDLOCK_TABLE_SQL =
            "CREATE TABLE IF NOT EXISTS shedlock (" +
            "  name       VARCHAR(64)  NOT NULL," +
            "  lock_until TIMESTAMP    NOT NULL," +
            "  locked_at  TIMESTAMP    NOT NULL," +
            "  locked_by  VARCHAR(255) NOT NULL," +
            "  PRIMARY KEY (name)" +
            ")";

    private final JdbcTemplate jdbcTemplate;

    public ShedLockConfig(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 自动创建 shedlock 表（幂等：IF NOT EXISTS）。
     *
     * <p>bridge 不使用 Flyway，因此通过 @PostConstruct 在 Bean 初始化后建表。
     * 建表失败仅记录告警，不阻断启动（ShedLock 注解在无表时会被跳过，
     * 调度方法仍可执行，仅失去分布式锁保护）。</p>
     */
    @PostConstruct
    public void createShedLockTable() {
        try {
            jdbcTemplate.execute(CREATE_SHEDLOCK_TABLE_SQL);
            log.info("ShedLock table ensured (CREATE TABLE IF NOT EXISTS shedlock)");
        } catch (RuntimeException e) {
            log.warn("Failed to create shedlock table (ShedLock will be disabled): {}", e.getMessage());
        }
    }

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
     * @return JdbcTemplateLockProvider 实例
     */
    @Bean
    public LockProvider lockProvider() {
        JdbcTemplateLockProvider.Configuration configuration = JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .usingDbTime()
                .build();
        return new JdbcTemplateLockProvider(configuration);
    }
}