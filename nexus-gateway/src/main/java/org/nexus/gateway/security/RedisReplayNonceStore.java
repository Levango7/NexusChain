package org.nexus.gateway.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 版防重放 nonce 存储（多副本共享：SET NX + TTL）。
 *
 * <p>短期项 #4b：Helm 生产 gateway {@code minReplicas: 2}，进程内 nonce 表
 * 在 5 分钟窗口内允许跨 Pod 重放；Redis SET NX 使全部副本看到同一 nonce 键。</p>
 *
 * <p><b>装配条件（双保险，缺一不启用）</b>：
 * ① {@code nexus.security.replay-store=redis}（默认 memory——行为与改造前一致，
 * 不会因 Redis 未配置而把 prod 打挂）；② gateway 必须配好 {@code spring.data.redis}
 * 连接（prod 通过 NEX_REDIS_* 注入）。未满足时本 bean 不创建，
 * {@code RequestSignatureInterceptor} 经 ObjectProvider 兜底为内存版。</p>
 *
 * <p><b>fail-closed</b>：启用后 Redis 异常返回 false（视为重放拒绝），
 * 绝不因基础设施故障静默放行重放。</p>
 */
@Component
@Profile("prod")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "nexus.security.replay-store", havingValue = "redis", matchIfMissing = false)
public class RedisReplayNonceStore implements ReplayNonceStore {

    private static final Logger log = LoggerFactory.getLogger(RedisReplayNonceStore.class);
    /** key 前缀（与既有 idempotency 键风格一致）。 */
    static final String KEY_PREFIX = "nexus:replay-nonce:";

    private final StringRedisTemplate redisTemplate;

    public RedisReplayNonceStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean register(String nonce, long ttlMillis) {
        try {
            Boolean ok = redisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + nonce, "1", Duration.ofMillis(ttlMillis));
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException e) {
            log.error("Replay nonce store (redis) unavailable — fail-closed (treated as replay): {}", e.getMessage());
            return false;
        }
    }
}
