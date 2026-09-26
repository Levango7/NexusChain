package org.nexus.gateway.security.encryption;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 加密策略配置服务 — 管理租户/商户级加密配置。
 *
 * <p>核心职责：
 * <ul>
 *   <li>{@link #createConfig} — 创建加密策略配置</li>
 *   <li>{@link #getActiveConfig} — 获取生效配置（商户级优先，回退全局）</li>
 *   <li>{@link #updateConfig} — 更新加密策略配置</li>
 * </ul>
 *
 * <p><b>配置优先级</b>：商户级配置（merchant_id 非 NULL）优先于全局配置（merchant_id NULL）。
 * 查询时先查商户级，无则回退全局配置。</p>
 *
 * <p><b>算法校验</b>：仅允许 AES-256-GCM，其他算法拒绝创建（错误码 UNSUPPORTED_ENCRYPTION_ALGORITHM）。
 * 来源：设计文档 §4.1.1、§5.1.1 决策 1。</p>
 */
@Service
public class EncryptionConfigService {

    private static final Logger log = LoggerFactory.getLogger(EncryptionConfigService.class);

    private static final String ALLOWED_ALGORITHM = "AES-256-GCM";

    private final EncryptionConfigRepository configRepository;
    private final ObjectMapper objectMapper;

    public EncryptionConfigService(EncryptionConfigRepository configRepository,
                                   ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建加密策略配置。
     *
     * @param tenantId                  租户 ID
     * @param merchantId                商户 ID（NULL 表示全局策略）
     * @param encryptedFields           需加密的字段名列表
     * @param encryptionAlgorithm       加密算法（仅允许 AES-256-GCM）
     * @param kekRotationPeriodDays     KEK 轮换周期（天）
     * @param appLayerEncryptionEnabled 应用层加密开关
     * @return 创建的配置实体
     * @throws EncryptionException 如果算法不支持
     */
    @Transactional
    public EncryptionConfig createConfig(String tenantId, Long merchantId,
                                          List<String> encryptedFields,
                                          String encryptionAlgorithm,
                                          Integer kekRotationPeriodDays,
                                          Boolean appLayerEncryptionEnabled) {
        // 校验加密算法
        if (!ALLOWED_ALGORITHM.equals(encryptionAlgorithm)) {
            log.warn("不支持的加密算法: {} — 仅允许 {}", encryptionAlgorithm, ALLOWED_ALGORITHM);
            throw new EncryptionException("UNSUPPORTED_ENCRYPTION_ALGORITHM",
                    "不支持的加密算法: " + encryptionAlgorithm + "，仅允许 " + ALLOWED_ALGORITHM);
        }

        EncryptionConfig config = new EncryptionConfig();
        config.setTenantId(tenantId);
        config.setMerchantId(merchantId);
        config.setEncryptedFields(serializeFields(encryptedFields));
        config.setEncryptionAlgorithm(encryptionAlgorithm);
        config.setKekRotationPeriodDays(kekRotationPeriodDays != null ? kekRotationPeriodDays : 90);
        config.setAppLayerEncryptionEnabled(appLayerEncryptionEnabled != null ? appLayerEncryptionEnabled : true);

        return configRepository.save(config);
    }

    /**
     * 获取生效的加密配置 — 商户级优先，无则回退全局配置。
     *
     * @param tenantId   租户 ID
     * @param merchantId 商户 ID
     * @return 生效的加密配置，无配置时返回 empty
     */
    public Optional<EncryptionConfig> getActiveConfig(String tenantId, Long merchantId) {
        // 1. 先查商户级配置
        Optional<EncryptionConfig> merchantConfig =
                configRepository.findByTenantIdAndMerchantId(tenantId, merchantId);
        if (merchantConfig.isPresent()) {
            return merchantConfig;
        }

        // 2. 回退全局配置
        return configRepository.findByTenantIdAndMerchantIdIsNull(tenantId);
    }

    /**
     * 获取生效的加密配置（仅租户级全局配置）。
     *
     * @param tenantId 租户 ID
     * @return 全局加密配置，无配置时返回 empty
     */
    public Optional<EncryptionConfig> getGlobalConfig(String tenantId) {
        return configRepository.findByTenantIdAndMerchantIdIsNull(tenantId);
    }

    /**
     * 更新加密策略配置。
     *
     * @param configId                  配置 ID
     * @param encryptedFields           新的加密字段列表
     * @param encryptionAlgorithm       新的加密算法
     * @param kekRotationPeriodDays     新的轮换周期
     * @param appLayerEncryptionEnabled 新的应用层加密开关
     * @return 更新后的配置实体
     * @throws EncryptionException 如果配置不存在或算法不支持
     */
    @Transactional
    public EncryptionConfig updateConfig(Long configId,
                                          List<String> encryptedFields,
                                          String encryptionAlgorithm,
                                          Integer kekRotationPeriodDays,
                                          Boolean appLayerEncryptionEnabled) {
        EncryptionConfig config = configRepository.findById(configId)
                .orElseThrow(() -> new EncryptionException("CONFIG_NOT_FOUND",
                        "加密配置不存在: " + configId));

        if (encryptionAlgorithm != null && !ALLOWED_ALGORITHM.equals(encryptionAlgorithm)) {
            log.warn("不支持的加密算法: {} — 仅允许 {}", encryptionAlgorithm, ALLOWED_ALGORITHM);
            throw new EncryptionException("UNSUPPORTED_ENCRYPTION_ALGORITHM",
                    "不支持的加密算法: " + encryptionAlgorithm);
        }

        if (encryptedFields != null) {
            config.setEncryptedFields(serializeFields(encryptedFields));
        }
        if (encryptionAlgorithm != null) {
            config.setEncryptionAlgorithm(encryptionAlgorithm);
        }
        if (kekRotationPeriodDays != null) {
            config.setKekRotationPeriodDays(kekRotationPeriodDays);
        }
        if (appLayerEncryptionEnabled != null) {
            config.setAppLayerEncryptionEnabled(appLayerEncryptionEnabled);
        }

        return configRepository.save(config);
    }

    /**
     * 按配置 ID 查询。
     */
    public Optional<EncryptionConfig> findById(Long configId) {
        return configRepository.findById(configId);
    }

    /**
     * 解析加密字段列表（JSON → List<String>）。
     */
    public List<String> parseEncryptedFields(EncryptionConfig config) {
        if (config == null || config.getEncryptedFields() == null) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(config.getEncryptedFields(),
                    new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.error("加密字段列表 JSON 解析失败: {}", config.getEncryptedFields(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 序列化加密字段列表（List<String> → JSON）。
     */
    private String serializeFields(List<String> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new EncryptionException("SERIALIZATION_ERROR", "加密字段列表序列化失败", e);
        }
    }
}