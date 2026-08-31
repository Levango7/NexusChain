package org.nexus.gateway.config;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试环境的内存 LockProvider（S3/S1 测试适配，2026-08-31 交付前审计）。
 *
 * <p>主 ShedLockConfig 的 JDBC LockProvider（usingDbTime）在 test profile 排除：
 * H2 测试库不认其生成的 MySQL 方言 SQL（TIMESTAMPADD/MICROSECOND），且
 * shedlock 契约表无 JPA 实体、ddl-auto 不建表——启动期首个 @Scheduled 任务
 * 打表失败，异常冒泡为端点 500（v2 商户流测试实测）。</p>
 *
 * <p>测试不需要真实跨实例互斥（单上下文），内存实现按 lockAtMostFor
 * 语义持有锁即满足 @SchedulerLock 契约。</p>
 */
@Configuration
// v2 集成测试用 sandbox profile、PaymentServiceTest 系用 test profile——两者都需要内存 LockProvider
@Profile({"test", "sandbox"})
public class TestShedLockConfig {

    /** 内存锁记录：lockName → 持有截止时间戳（epoch millis）。 */
    private static final Map<String, Long> LOCKS = new ConcurrentHashMap<>();

    @Bean
    public LockProvider lockProvider() {
        return lockConfiguration -> {
            String name = lockConfiguration.getName();
            long now = System.currentTimeMillis();
            long lockUntil = lockConfiguration.getLockAtMostFor().toMillis() + now;
            // CAS 语义：仅当未持有或已过期时获得锁
            Long actual = LOCKS.compute(name, (k, heldUntil) ->
                    heldUntil == null || heldUntil < now ? lockUntil : heldUntil);
            if (actual != lockUntil) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new net.javacrumbs.shedlock.core.SimpleLock() {
                @Override
                public void unlock() {
                    LOCKS.remove(name);
                }
            });
        };
    }
}
