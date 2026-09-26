package org.nexus.gateway.security.password;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 密码安全策略配置服务（PolicyService）。
 *
 * <p>按租户维度管理密码安全策略配置。未配置的租户使用默认值：
 * <ul>
 *   <li>minLength=8, requireUppercase=true, requireLowercase=true</li>
 *   <li>requireDigit=true, requireSpecialChar=true</li>
 *   <li>maxFailedAttempts=5, lockDurationMinutes=30</li>
 *   <li>passwordExpiryDays=90, passwordHistoryCount=5</li>
 * </ul></p>
 *
 * <p>来源：设计文档 §5.4.1 PasswordSecurityPolicyService。</p>
 */
@Service
public class PasswordSecurityConfigService {

    private static final Logger log = LoggerFactory.getLogger(PasswordSecurityConfigService.class);

    // --- 默认值常量 ---
    public static final int DEFAULT_MIN_LENGTH = 8;
    public static final boolean DEFAULT_REQUIRE_UPPERCASE = true;
    public static final boolean DEFAULT_REQUIRE_LOWERCASE = true;
    public static final boolean DEFAULT_REQUIRE_DIGIT = true;
    public static final boolean DEFAULT_REQUIRE_SPECIAL_CHAR = true;
    public static final int DEFAULT_MAX_FAILED_ATTEMPTS = 5;
    public static final int DEFAULT_LOCK_DURATION_MINUTES = 30;
    public static final int DEFAULT_PASSWORD_EXPIRY_DAYS = 90;
    public static final int DEFAULT_PASSWORD_HISTORY_COUNT = 5;

    private final PasswordSecurityConfigRepository repository;

    public PasswordSecurityConfigService(PasswordSecurityConfigRepository repository) {
        this.repository = repository;
    }

    /**
     * 查询租户的密码安全策略配置。无配置时返回默认值。
     *
     * @param tenantId 租户 ID
     * @return 配置对象（数据库记录或默认值）
     */
    @Transactional(readOnly = true)
    public PasswordSecurityConfig getConfig(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return createDefaultConfig(null);
        }
        return repository.findByTenantId(tenantId)
                .orElseGet(() -> createDefaultConfig(tenantId));
    }

    /**
     * 更新租户的密码安全策略配置。不存在则创建。
     *
     * @param config 配置对象（tenantId 必填）
     * @return 保存后的配置对象
     */
    @Transactional
    public PasswordSecurityConfig updateConfig(PasswordSecurityConfig config) {
        if (config.getTenantId() == null || config.getTenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        Instant now = Instant.now();
        PasswordSecurityConfig existing = repository.findByTenantId(config.getTenantId())
                .orElse(null);
        if (existing != null) {
            // 更新已有记录，保留 id 和 version
            existing.setMinLength(config.getMinLength());
            existing.setRequireUppercase(config.isRequireUppercase());
            existing.setRequireLowercase(config.isRequireLowercase());
            existing.setRequireDigit(config.isRequireDigit());
            existing.setRequireSpecialChar(config.isRequireSpecialChar());
            existing.setMaxFailedAttempts(config.getMaxFailedAttempts());
            existing.setLockDurationMinutes(config.getLockDurationMinutes());
            existing.setPasswordExpiryDays(config.getPasswordExpiryDays());
            existing.setPasswordHistoryCount(config.getPasswordHistoryCount());
            existing.setSecondFactorRequired(config.isSecondFactorRequired());
            existing.setSecondFactorMethods(config.getSecondFactorMethods());
            existing.setUpdatedAt(now);
            return repository.save(existing);
        } else {
            config.setCreatedAt(now);
            config.setUpdatedAt(now);
            return repository.save(config);
        }
    }

    /**
     * 创建默认配置对象（不持久化）。
     *
     * @param tenantId 租户 ID（可为 null）
     * @return 包含默认值的配置对象
     */
    private PasswordSecurityConfig createDefaultConfig(String tenantId) {
        PasswordSecurityConfig config = new PasswordSecurityConfig();
        config.setTenantId(tenantId);
        config.setMinLength(DEFAULT_MIN_LENGTH);
        config.setRequireUppercase(DEFAULT_REQUIRE_UPPERCASE);
        config.setRequireLowercase(DEFAULT_REQUIRE_LOWERCASE);
        config.setRequireDigit(DEFAULT_REQUIRE_DIGIT);
        config.setRequireSpecialChar(DEFAULT_REQUIRE_SPECIAL_CHAR);
        config.setMaxFailedAttempts(DEFAULT_MAX_FAILED_ATTEMPTS);
        config.setLockDurationMinutes(DEFAULT_LOCK_DURATION_MINUTES);
        config.setPasswordExpiryDays(DEFAULT_PASSWORD_EXPIRY_DAYS);
        config.setPasswordHistoryCount(DEFAULT_PASSWORD_HISTORY_COUNT);
        return config;
    }

    /**
     * 校验密码复杂度。
     *
     * <p>来源：设计文档 §5.4.1 validateComplexity。</p>
     *
     * @param password 明文密码
     * @param config   安全策略配置
     * @throws IllegalArgumentException 密码不满足复杂度要求
     */
    public void validateComplexity(String password, PasswordSecurityConfig config) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("PASSWORD_TOO_SHORT: 密码不能为空");
        }
        if (password.length() < config.getMinLength()) {
            throw new IllegalArgumentException(
                    "PASSWORD_TOO_SHORT: 密码长度不足，最小要求 " + config.getMinLength() + " 位");
        }
        if (config.isRequireUppercase() && !password.matches(".*[A-Z].*")) {
            throw new IllegalArgumentException(
                    "PASSWORD_COMPLEXITY_INSUFFICIENT: 密码需包含大写字母");
        }
        if (config.isRequireLowercase() && !password.matches(".*[a-z].*")) {
            throw new IllegalArgumentException(
                    "PASSWORD_COMPLEXITY_INSUFFICIENT: 密码需包含小写字母");
        }
        if (config.isRequireDigit() && !password.matches(".*\\d.*")) {
            throw new IllegalArgumentException(
                    "PASSWORD_COMPLEXITY_INSUFFICIENT: 密码需包含数字");
        }
        if (config.isRequireSpecialChar() && !password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?].*")) {
            throw new IllegalArgumentException(
                    "PASSWORD_COMPLEXITY_INSUFFICIENT: 密码需包含特殊字符");
        }
    }
}