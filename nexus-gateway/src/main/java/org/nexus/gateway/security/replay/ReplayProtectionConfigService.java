package org.nexus.gateway.security.replay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 防重放保护配置服务。
 *
 * <p>提供按租户查询和更新防重放参数的能力。查询时若租户无配置记录，
 * 返回默认值（replayWindowMs=180000, nonceMinLengthBytes=16, idempotencyTtlHours=24）。
 * 更新时校验参数范围，超出范围则抛出 {@link IllegalArgumentException}。</p>
 *
 * <p>设计依据：Wave 12 设计文档 §2.2.2、§4.2.1、§5.2 决策5。</p>
 */
@Service
public class ReplayProtectionConfigService {

    private static final Logger log = LoggerFactory.getLogger(ReplayProtectionConfigService.class);

    /** 默认防重放窗口：3 分钟。 */
    static final long DEFAULT_REPLAY_WINDOW_MS = 180000L;
    /** 默认 nonce 最小长度：16 字节（128 位）。 */
    static final int DEFAULT_NONCE_MIN_LENGTH_BYTES = 16;
    /** 默认幂等性键 TTL：24 小时。 */
    static final int DEFAULT_IDEMPOTENCY_TTL_HOURS = 24;

    /** 防重放窗口范围：1 分钟 ~ 5 分钟。 */
    static final long MIN_REPLAY_WINDOW_MS = 60000L;
    static final long MAX_REPLAY_WINDOW_MS = 300000L;
    /** nonce 最小长度下限：16 字节。 */
    static final int MIN_NONCE_LENGTH_BYTES = 16;
    /** 幂等性键 TTL 范围：1 ~ 168 小时（7 天）。 */
    static final int MIN_IDEMPOTENCY_TTL_HOURS = 1;
    static final int MAX_IDEMPOTENCY_TTL_HOURS = 168;

    private final ReplayProtectionConfigRepository repository;

    public ReplayProtectionConfigService(ReplayProtectionConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * 查询租户的防重放配置。无配置记录时返回默认值。
     *
     * @param tenantId 租户 ID
     * @return 配置实体（可能是默认值构造的临时对象，未持久化）
     */
    @Transactional(readOnly = true)
    public ReplayProtectionConfig getConfig(String tenantId) {
        Optional<ReplayProtectionConfig> opt = repository.findByTenantId(tenantId);
        if (opt.isPresent()) {
            return opt.get();
        }
        // 无配置记录时返回默认值
        ReplayProtectionConfig defaults = new ReplayProtectionConfig();
        defaults.setTenantId(tenantId);
        defaults.setReplayWindowMs(DEFAULT_REPLAY_WINDOW_MS);
        defaults.setNonceMinLengthBytes(DEFAULT_NONCE_MIN_LENGTH_BYTES);
        defaults.setIdempotencyTtlHours(DEFAULT_IDEMPOTENCY_TTL_HOURS);
        return defaults;
    }

    /**
     * 创建或更新租户的防重放配置。
     *
     * <p>校验规则：
     * <ul>
     *   <li>replayWindowMs: 60000~300000（低于最小值使用最小值，高于最大值使用最大值）</li>
     *   <li>nonceMinLengthBytes: ≥ 16</li>
     *   <li>idempotencyTtlHours: 1~168</li>
     * </ul></p>
     *
     * @param tenantId              租户 ID
     * @param replayWindowMs        防重放窗口（毫秒）
     * @param nonceMinLengthBytes   nonce 最小长度（字节）
     * @param idempotencyTtlHours   幂等性键 TTL（小时）
     * @return 已保存的配置实体
     * @throws IllegalArgumentException 参数超出允许范围
     */
    @Transactional
    public ReplayProtectionConfig updateConfig(String tenantId, Long replayWindowMs,
                                                Integer nonceMinLengthBytes, Integer idempotencyTtlHours) {
        // 参数范围校验
        validateReplayWindowMs(replayWindowMs);
        validateNonceMinLengthBytes(nonceMinLengthBytes);
        validateIdempotencyTtlHours(idempotencyTtlHours);

        ReplayProtectionConfig config = repository.findByTenantId(tenantId)
                .orElseGet(() -> {
                    ReplayProtectionConfig newConfig = new ReplayProtectionConfig();
                    newConfig.setTenantId(tenantId);
                    return newConfig;
                });

        config.setReplayWindowMs(replayWindowMs);
        config.setNonceMinLengthBytes(nonceMinLengthBytes);
        config.setIdempotencyTtlHours(idempotencyTtlHours);

        ReplayProtectionConfig saved = repository.save(config);
        log.info("Updated replay protection config for tenant={}: replayWindowMs={}, nonceMinLengthBytes={}, idempotencyTtlHours={}",
                tenantId, replayWindowMs, nonceMinLengthBytes, idempotencyTtlHours);
        return saved;
    }

    private void validateReplayWindowMs(Long replayWindowMs) {
        if (replayWindowMs == null) {
            throw new IllegalArgumentException("replayWindowMs must not be null");
        }
        if (replayWindowMs < MIN_REPLAY_WINDOW_MS || replayWindowMs > MAX_REPLAY_WINDOW_MS) {
            throw new IllegalArgumentException(
                    "replayWindowMs must be between " + MIN_REPLAY_WINDOW_MS + " and " + MAX_REPLAY_WINDOW_MS);
        }
    }

    private void validateNonceMinLengthBytes(Integer nonceMinLengthBytes) {
        if (nonceMinLengthBytes == null) {
            throw new IllegalArgumentException("nonceMinLengthBytes must not be null");
        }
        if (nonceMinLengthBytes < MIN_NONCE_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "nonceMinLengthBytes must be >= " + MIN_NONCE_LENGTH_BYTES);
        }
    }

    private void validateIdempotencyTtlHours(Integer idempotencyTtlHours) {
        if (idempotencyTtlHours == null) {
            throw new IllegalArgumentException("idempotencyTtlHours must not be null");
        }
        if (idempotencyTtlHours < MIN_IDEMPOTENCY_TTL_HOURS || idempotencyTtlHours > MAX_IDEMPOTENCY_TTL_HOURS) {
            throw new IllegalArgumentException(
                    "idempotencyTtlHours must be between " + MIN_IDEMPOTENCY_TTL_HOURS + " and " + MAX_IDEMPOTENCY_TTL_HOURS);
        }
    }
}