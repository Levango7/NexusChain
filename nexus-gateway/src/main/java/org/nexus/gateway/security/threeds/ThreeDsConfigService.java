package org.nexus.gateway.security.threeds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3DS 配置服务 — 管理租户级和商户级 3DS 参数配置。
 *
 * <p>配置查询采用两级回退策略：
 * <ol>
 *   <li>优先查询商户级配置（tenantId + merchantId）</li>
 *   <li>无商户级配置时回退到租户级配置（tenantId + merchantId IS NULL）</li>
 *   <li>均无配置时返回默认配置（enabled=false, frictionlessThresholdScore=60, challengeTimeoutSeconds=300）</li>
 * </ol>
 * </p>
 */
@Service
public class ThreeDsConfigService {

    private static final Logger log = LoggerFactory.getLogger(ThreeDsConfigService.class);

    /** 默认 Frictionless 阈值分数。 */
    public static final int DEFAULT_FRICTIONLESS_THRESHOLD_SCORE = 60;

    /** 默认 Challenge 超时时间（秒）。 */
    public static final int DEFAULT_CHALLENGE_TIMEOUT_SECONDS = 300;

    private final ThreeDsConfigRepository configRepository;

    public ThreeDsConfigService(ThreeDsConfigRepository configRepository) {
        this.configRepository = configRepository;
    }

    /**
     * 查询 3DS 配置 — 商户级优先，无则回退租户级，再无则返回默认配置。
     *
     * @param tenantId   租户 ID
     * @param merchantId 商户 ID
     * @return 3DS 配置（不为 null）
     */
    @Transactional(readOnly = true)
    public ThreeDsConfig getConfig(String tenantId, Long merchantId) {
        // 1. 商户级配置优先
        if (merchantId != null) {
            var merchantConfig = configRepository.findByTenantIdAndMerchantId(tenantId, merchantId);
            if (merchantConfig.isPresent()) {
                log.debug("使用商户级 3DS 配置: tenantId={}, merchantId={}", tenantId, merchantId);
                return merchantConfig.get();
            }
        }

        // 2. 回退租户级配置
        var tenantConfig = configRepository.findByTenantIdAndMerchantIdIsNull(tenantId);
        if (tenantConfig.isPresent()) {
            log.debug("使用租户级 3DS 配置: tenantId={}", tenantId);
            return tenantConfig.get();
        }

        // 3. 返回默认配置
        log.debug("无 3DS 配置，使用默认值: tenantId={}, merchantId={}", tenantId, merchantId);
        return createDefaultConfig(tenantId, merchantId);
    }

    /**
     * 更新 3DS 配置 — 不存在则创建。
     *
     * @param tenantId   租户 ID
     * @param merchantId 商户 ID（NULL 表示租户级配置）
     * @param enabled    3DS 启用开关
     * @param frictionlessThresholdScore Frictionless 阈值分数
     * @param challengeTimeoutSeconds    Challenge 超时时间（秒）
     * @param acsUrl     ACS URL
     * @return 更新后的配置
     */
    @Transactional
    public ThreeDsConfig updateConfig(String tenantId, Long merchantId, boolean enabled,
                                      int frictionlessThresholdScore, int challengeTimeoutSeconds,
                                      String acsUrl) {
        ThreeDsConfig config;

        if (merchantId != null) {
            config = configRepository.findByTenantIdAndMerchantId(tenantId, merchantId)
                    .orElseGet(() -> {
                        ThreeDsConfig newConfig = new ThreeDsConfig();
                        newConfig.setTenantId(tenantId);
                        newConfig.setMerchantId(merchantId);
                        return newConfig;
                    });
        } else {
            config = configRepository.findByTenantIdAndMerchantIdIsNull(tenantId)
                    .orElseGet(() -> {
                        ThreeDsConfig newConfig = new ThreeDsConfig();
                        newConfig.setTenantId(tenantId);
                        newConfig.setMerchantId(null);
                        return newConfig;
                    });
        }

        config.setEnabled(enabled);
        config.setFrictionlessThresholdScore(frictionlessThresholdScore);
        config.setChallengeTimeoutSeconds(challengeTimeoutSeconds);
        config.setAcsUrl(acsUrl);

        return configRepository.save(config);
    }

    /**
     * 创建默认配置对象（不持久化）。
     */
    private ThreeDsConfig createDefaultConfig(String tenantId, Long merchantId) {
        ThreeDsConfig config = new ThreeDsConfig();
        config.setTenantId(tenantId);
        config.setMerchantId(merchantId);
        config.setEnabled(false);
        config.setFrictionlessThresholdScore(DEFAULT_FRICTIONLESS_THRESHOLD_SCORE);
        config.setChallengeTimeoutSeconds(DEFAULT_CHALLENGE_TIMEOUT_SECONDS);
        return config;
    }
}