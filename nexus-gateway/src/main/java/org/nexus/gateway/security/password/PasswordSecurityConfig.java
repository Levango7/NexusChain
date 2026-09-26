package org.nexus.gateway.security.password;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 密码安全策略配置实体，映射 password_security_configs 表。
 *
 * <p>按租户维度配置密码复杂度要求、失败锁定策略、过期周期、历史重复检查数、
 * 二次验证要求等。未配置的租户使用 {@link PasswordSecurityConfigService} 中的默认值。</p>
 *
 * <p>使用 {@code @Version} 实现乐观锁，防止并发更新配置。</p>
 */
@Entity
@Table(name = "password_security_configs")
public class PasswordSecurityConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "min_length", nullable = false)
    private int minLength = 8;

    @Column(name = "require_uppercase", nullable = false)
    private boolean requireUppercase = true;

    @Column(name = "require_lowercase", nullable = false)
    private boolean requireLowercase = true;

    @Column(name = "require_digit", nullable = false)
    private boolean requireDigit = true;

    @Column(name = "require_special_char", nullable = false)
    private boolean requireSpecialChar = true;

    @Column(name = "max_failed_attempts", nullable = false)
    private int maxFailedAttempts = 5;

    @Column(name = "lock_duration_minutes", nullable = false)
    private int lockDurationMinutes = 30;

    @Column(name = "password_expiry_days", nullable = false)
    private int passwordExpiryDays = 90;

    @Column(name = "password_history_count", nullable = false)
    private int passwordHistoryCount = 5;

    @Column(name = "second_factor_required", nullable = false)
    private boolean secondFactorRequired = false;

    @Column(name = "second_factor_methods", nullable = false, length = 128)
    private String secondFactorMethods = "OTP";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public int getMinLength() { return minLength; }
    public void setMinLength(int minLength) { this.minLength = minLength; }

    public boolean isRequireUppercase() { return requireUppercase; }
    public void setRequireUppercase(boolean requireUppercase) { this.requireUppercase = requireUppercase; }

    public boolean isRequireLowercase() { return requireLowercase; }
    public void setRequireLowercase(boolean requireLowercase) { this.requireLowercase = requireLowercase; }

    public boolean isRequireDigit() { return requireDigit; }
    public void setRequireDigit(boolean requireDigit) { this.requireDigit = requireDigit; }

    public boolean isRequireSpecialChar() { return requireSpecialChar; }
    public void setRequireSpecialChar(boolean requireSpecialChar) { this.requireSpecialChar = requireSpecialChar; }

    public int getMaxFailedAttempts() { return maxFailedAttempts; }
    public void setMaxFailedAttempts(int maxFailedAttempts) { this.maxFailedAttempts = maxFailedAttempts; }

    public int getLockDurationMinutes() { return lockDurationMinutes; }
    public void setLockDurationMinutes(int lockDurationMinutes) { this.lockDurationMinutes = lockDurationMinutes; }

    public int getPasswordExpiryDays() { return passwordExpiryDays; }
    public void setPasswordExpiryDays(int passwordExpiryDays) { this.passwordExpiryDays = passwordExpiryDays; }

    public int getPasswordHistoryCount() { return passwordHistoryCount; }
    public void setPasswordHistoryCount(int passwordHistoryCount) { this.passwordHistoryCount = passwordHistoryCount; }

    public boolean isSecondFactorRequired() { return secondFactorRequired; }
    public void setSecondFactorRequired(boolean secondFactorRequired) { this.secondFactorRequired = secondFactorRequired; }

    public String getSecondFactorMethods() { return secondFactorMethods; }
    public void setSecondFactorMethods(String secondFactorMethods) { this.secondFactorMethods = secondFactorMethods; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}