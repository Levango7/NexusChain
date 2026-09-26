package org.nexus.gateway.security.encryption;

import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JPA EntityListener — 持久化前加密、查询后解密敏感字段。
 *
 * <p>通过 JPA {@code @EntityListeners} 机制集成，对业务层透明：
 * <ul>
 *   <li>{@code @PrePersist / @PreUpdate} — 加密敏感字段（明文 → 密文）</li>
 *   <li>{@code @PostLoad} — 解密敏感字段（密文 → 明文）</li>
 * </ul>
 *
 * <p><b>渐进式迁移</b>：旧数据无加密标记（encryptionMetadataId 为 NULL）则直接返回明文，
 * 不执行解密操作。新数据写入时自动加密并关联密钥元数据。</p>
 *
 * <p><b>fail-closed 策略</b>：KEK 不可用时拒绝写入加密字段（抛异常），
 * 不允许静默降级为明文存储。
 * 来源：经验 2026-09-10-java-springboot-oidc-fallback-hmac-fail-closed-fix</p>
 *
 * <p>设计文档 §6.5.1 — 字段级加密集成（JPA EntityListener）。</p>
 *
 * <p><b>注意</b>：EntityListener 由 JPA 框架实例化，不通过 Spring DI 注入。
 * 使用 {@code @Autowired} + 字段注入 + Spring 的 AspectJ 静态织入或
 * {@code ConfigurableObjectSupport} 模式注入依赖。在测试环境中需手动注入。</p>
 */
@Component
public class FieldEncryptionListener {

    private static final Logger log = LoggerFactory.getLogger(FieldEncryptionListener.class);

    private static final String ENCRYPTION_MARKER_PREFIX = "ENC:";

    /** 反射缓存：类 → 字段名 → Field 对象（用 Optional 包装，避免 ConcurrentHashMap null value 问题） */
    private final Map<Class<?>, Map<String, java.util.Optional<Field>>> fieldCache = new ConcurrentHashMap<>();

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private EncryptionConfigService encryptionConfigService;

    @Autowired
    private KeyManagementService keyManagementService;

    @Autowired
    private EncryptionKeyMetadataRepository metadataRepository;

    /**
     * 持久化前加密 — 加密标记为需加密的字段。
     *
     * <p>流程：
     * <ol>
     *   <li>获取对象的租户 ID 和商户 ID</li>
     *   <li>查询生效的加密配置</li>
     *   <li>对配置中指定的字段执行加密</li>
     *   <li>加密后的值以 {@code "ENC:"} 前缀标记，区分明文和密文</li>
     * </ol>
     *
     * <p>KEK 不可用时 fail-closed — 抛异常拒绝写入。</p>
     */
    @PrePersist
    @PreUpdate
    public void encrypt(Object entity) {
        if (entity == null) {
            return;
        }

        // fail-closed: KEK 不可用时不允许写入加密字段
        if (!keyManagementService.isAvailable()) {
            log.warn("禁止回退：KEK 不可用 — fail-closed，拒绝写入加密字段");
            throw new EncryptionException("ENCRYPTION_KEY_UNAVAILABLE",
                    "KEK 不可用，拒绝写入加密字段");
        }

        String tenantId = getTenantId(entity);
        Long merchantId = getMerchantId(entity);
        if (tenantId == null) {
            return; // 无租户上下文，跳过加密
        }

        EncryptionConfig config = encryptionConfigService.getActiveConfig(tenantId, merchantId)
                .orElse(null);
        if (config == null) {
            return; // 无加密配置，跳过
        }

        List<String> fieldsToEncrypt = encryptionConfigService.parseEncryptedFields(config);
        if (fieldsToEncrypt.isEmpty()) {
            return;
        }

        int kekVersion = keyManagementService.getCurrentKekVersion();

        for (String fieldName : fieldsToEncrypt) {
            String plaintext = getFieldValue(entity, fieldName);
            if (plaintext == null || plaintext.isEmpty()) {
                continue;
            }

            // 已加密的数据不重复加密
            if (plaintext.startsWith(ENCRYPTION_MARKER_PREFIX)) {
                continue;
            }

            // 获取或创建 DEK（同一 tenant+field+kekVersion 复用同一 DEK，避免配对不一致）
            byte[] dek;
            byte[] encryptedDek;
            EncryptionKeyMetadata existingMetadata = metadataRepository
                    .findByTenantIdAndFieldNameAndKekVersion(tenantId, fieldName, kekVersion)
                    .orElse(null);
            if (existingMetadata != null) {
                // 复用已有 DEK：解密获取明文 DEK
                dek = keyManagementService.decryptDekCached(
                        existingMetadata.getId(), existingMetadata.getEncryptedDek(), kekVersion);
                encryptedDek = existingMetadata.getEncryptedDek();
            } else {
                // 首次加密：生成新 DEK
                dek = keyManagementService.generateDek();
                encryptedDek = keyManagementService.encryptDek(dek, kekVersion);
            }

            // 加密字段
            EncryptedField encrypted = encryptionService.encrypt(plaintext, dek);

            // 构造加密标记值：ENC:<metadataId>:<ivBase64>:<ciphertextBase64>
            // metadataId 在 PostLoad 时用于查找 DEK 解密
            // 此处先写入密文，metadataId 在后续保存时关联
            String encryptedValue = ENCRYPTION_MARKER_PREFIX + kekVersion + ":" +
                    encrypted.getIvBase64() + ":" + encrypted.getCiphertextBase64();

            setFieldValue(entity, fieldName, encryptedValue);

            // 保存 DEK 元数据
            saveKeyMetadata(tenantId, fieldName, kekVersion, encryptedDek, encrypted.getIv());
        }
    }

    /**
     * 查询后解密 — 解密以 {@code "ENC:"} 前缀标记的加密字段。
     *
     * <p>渐进式迁移：旧数据无加密标记则直接返回明文。</p>
     */
    @PostLoad
    public void decrypt(Object entity) {
        if (entity == null) {
            return;
        }

        String tenantId = getTenantId(entity);
        Long merchantId = getMerchantId(entity);
        if (tenantId == null) {
            return;
        }

        EncryptionConfig config = encryptionConfigService.getActiveConfig(tenantId, merchantId)
                .orElse(null);
        if (config == null) {
            return;
        }

        List<String> fieldsToDecrypt = encryptionConfigService.parseEncryptedFields(config);
        if (fieldsToDecrypt.isEmpty()) {
            return;
        }

        for (String fieldName : fieldsToDecrypt) {
            String value = getFieldValue(entity, fieldName);
            if (value == null || !value.startsWith(ENCRYPTION_MARKER_PREFIX)) {
                // 渐进式迁移：旧数据无加密标记，直接返回明文
                continue;
            }

            try {
                // 解析加密标记：ENC:<kekVersion>:<ivBase64>:<ciphertextBase64>
                String[] parts = value.substring(ENCRYPTION_MARKER_PREFIX.length()).split(":", 3);
                if (parts.length != 3) {
                    log.warn("加密字段格式错误: {}", fieldName);
                    continue;
                }

                int kekVersion = Integer.parseInt(parts[0]);
                byte[] iv = Base64.getDecoder().decode(parts[1]);
                byte[] ciphertext = Base64.getDecoder().decode(parts[2]);

                // 查找该字段的 DEK 元数据
                EncryptionKeyMetadata metadata = metadataRepository
                        .findByTenantIdAndFieldNameAndKekVersion(tenantId, fieldName, kekVersion)
                        .orElse(null);

                if (metadata == null) {
                    log.warn("加密密钥元数据不存在: tenant={}, field={}, kekVersion={}",
                            tenantId, fieldName, kekVersion);
                    continue;
                }

                // 解密 DEK
                byte[] dek = keyManagementService.decryptDekCached(
                        metadata.getId(), metadata.getEncryptedDek(), kekVersion);

                // 解密字段
                String plaintext = encryptionService.decrypt(ciphertext, iv, dek);
                setFieldValue(entity, fieldName, plaintext);
            } catch (Exception e) {
                log.warn("认证降级：字段解密失败 — fail-closed，字段: {}", fieldName, e);
                // fail-closed: 解密失败时不返回密文，设置为 null 防止泄露
                setFieldValue(entity, fieldName, null);
            }
        }
    }

    /**
     * 保存密钥元数据到数据库。
     */
    private void saveKeyMetadata(String tenantId, String fieldName, int kekVersion,
                                  byte[] encryptedDek, byte[] iv) {
        // 查找是否已存在该字段+版本的元数据
        EncryptionKeyMetadata existing = metadataRepository
                .findByTenantIdAndFieldNameAndKekVersion(tenantId, fieldName, kekVersion)
                .orElse(null);

        if (existing != null) {
            return; // 已存在，不重复创建
        }

        EncryptionKeyMetadata metadata = new EncryptionKeyMetadata();
        metadata.setTenantId(tenantId);
        metadata.setFieldName(fieldName);
        metadata.setKekVersion(kekVersion);
        metadata.setEncryptedDek(encryptedDek);
        metadata.setIv(iv);
        // authTag 在 GCM 模式下附加在 encryptedDek 的密文末尾，此处存储 iv 即可
        metadata.setAuthTag(new byte[0]); // authTag 包含在 encryptedDek 中
        metadata.setStatus(KeyMetadataStatus.ACTIVE);
        metadataRepository.save(metadata);
    }

    // --- 反射工具方法 ---

    private String getTenantId(Object entity) {
        try {
            Field field = getAccessibleField(entity.getClass(), "tenantId");
            if (field != null) {
                Object value = field.get(entity);
                return value != null ? value.toString() : null;
            }
        } catch (Exception e) {
            // 无 tenantId 字段
        }
        return null;
    }

    private Long getMerchantId(Object entity) {
        try {
            Field field = getAccessibleField(entity.getClass(), "merchantId");
            if (field != null) {
                Object value = field.get(entity);
                if (value instanceof Long) {
                    return (Long) value;
                }
                if (value != null) {
                    return Long.parseLong(value.toString());
                }
            }
        } catch (Exception e) {
            // 无 merchantId 字段
        }
        return null;
    }

    private String getFieldValue(Object entity, String fieldName) {
        try {
            Field field = getAccessibleField(entity.getClass(), fieldName);
            if (field != null) {
                Object value = field.get(entity);
                return value != null ? value.toString() : null;
            }
        } catch (Exception e) {
            log.debug("字段 {} 不存在于 {}", fieldName, entity.getClass().getSimpleName());
        }
        return null;
    }

    private void setFieldValue(Object entity, String fieldName, String value) {
        try {
            Field field = getAccessibleField(entity.getClass(), fieldName);
            if (field != null) {
                field.set(entity, value);
            }
        } catch (Exception e) {
            log.debug("无法设置字段 {} 于 {}", fieldName, entity.getClass().getSimpleName());
        }
    }

    private Field getAccessibleField(Class<?> clazz, String fieldName) {
        return fieldCache
                .computeIfAbsent(clazz, c -> new ConcurrentHashMap<>())
                .computeIfAbsent(fieldName, fn -> {
                    Class<?> searchType = clazz;
                    while (searchType != null && searchType != Object.class) {
                        try {
                            Field field = searchType.getDeclaredField(fn);
                            field.setAccessible(true);
                            return java.util.Optional.of(field);
                        } catch (NoSuchFieldException e) {
                            searchType = searchType.getSuperclass();
                        }
                    }
                    return java.util.Optional.empty(); // 字段不存在
                })
                .orElse(null);
    }
}