package org.nexus.gateway.security.password;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link PasswordSecurityConfigService} 单元测试。
 *
 * <p>覆盖 getConfig（默认值/数据库配置）、validateComplexity（各种密码校验场景）、
 * updateConfig（创建/更新配置）。</p>
 *
 * <p>经验来源：2026-09-21-jpa-repository-findall-pageable-mock-omission
 * （Repository 方法签名确认，findByTenantId 返回 Optional）。</p>
 */
@ExtendWith(MockitoExtension.class)
class PasswordSecurityConfigServiceTest {

    private static final String TENANT_ID = "tenant-001";

    @Mock
    private PasswordSecurityConfigRepository repository;

    private PasswordSecurityConfigService configService;

    @BeforeEach
    void setUp() {
        configService = new PasswordSecurityConfigService(repository);
    }

    // ==================== getConfig ====================

    @Test
    @DisplayName("getConfig: 数据库有配置时返回数据库配置")
    void getConfig_existingConfig_returnsFromDb() {
        PasswordSecurityConfig dbConfig = new PasswordSecurityConfig();
        dbConfig.setId(1L);
        dbConfig.setTenantId(TENANT_ID);
        dbConfig.setMinLength(12);
        dbConfig.setRequireUppercase(false);
        dbConfig.setRequireLowercase(true);
        dbConfig.setRequireDigit(true);
        dbConfig.setRequireSpecialChar(false);
        dbConfig.setMaxFailedAttempts(3);
        dbConfig.setLockDurationMinutes(60);
        dbConfig.setPasswordExpiryDays(30);
        dbConfig.setPasswordHistoryCount(3);

        when(repository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(dbConfig));

        PasswordSecurityConfig result = configService.getConfig(TENANT_ID);

        assertEquals(TENANT_ID, result.getTenantId());
        assertEquals(12, result.getMinLength());
        assertFalse(result.isRequireUppercase());
        assertEquals(3, result.getMaxFailedAttempts());
        assertEquals(60, result.getLockDurationMinutes());
        assertEquals(30, result.getPasswordExpiryDays());
        assertEquals(3, result.getPasswordHistoryCount());
    }

    @Test
    @DisplayName("getConfig: 无配置时返回默认值")
    void getConfig_noConfig_returnsDefault() {
        when(repository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());

        PasswordSecurityConfig result = configService.getConfig(TENANT_ID);

        assertEquals(TENANT_ID, result.getTenantId());
        assertEquals(PasswordSecurityConfigService.DEFAULT_MIN_LENGTH, result.getMinLength());
        assertEquals(PasswordSecurityConfigService.DEFAULT_REQUIRE_UPPERCASE, result.isRequireUppercase());
        assertEquals(PasswordSecurityConfigService.DEFAULT_REQUIRE_LOWERCASE, result.isRequireLowercase());
        assertEquals(PasswordSecurityConfigService.DEFAULT_REQUIRE_DIGIT, result.isRequireDigit());
        assertEquals(PasswordSecurityConfigService.DEFAULT_REQUIRE_SPECIAL_CHAR, result.isRequireSpecialChar());
        assertEquals(PasswordSecurityConfigService.DEFAULT_MAX_FAILED_ATTEMPTS, result.getMaxFailedAttempts());
        assertEquals(PasswordSecurityConfigService.DEFAULT_LOCK_DURATION_MINUTES, result.getLockDurationMinutes());
        assertEquals(PasswordSecurityConfigService.DEFAULT_PASSWORD_EXPIRY_DAYS, result.getPasswordExpiryDays());
        assertEquals(PasswordSecurityConfigService.DEFAULT_PASSWORD_HISTORY_COUNT, result.getPasswordHistoryCount());
    }

    @Test
    @DisplayName("getConfig: tenantId 为 null 时返回默认值（tenantId=null）")
    void getConfig_nullTenantId_returnsDefault() {
        PasswordSecurityConfig result = configService.getConfig(null);

        assertNull(result.getTenantId());
        assertEquals(PasswordSecurityConfigService.DEFAULT_MIN_LENGTH, result.getMinLength());
        verify(repository, never()).findByTenantId(any());
    }

    @Test
    @DisplayName("getConfig: tenantId 为空字符串时返回默认值")
    void getConfig_blankTenantId_returnsDefault() {
        PasswordSecurityConfig result = configService.getConfig("");

        assertNull(result.getTenantId());
        assertEquals(PasswordSecurityConfigService.DEFAULT_MIN_LENGTH, result.getMinLength());
        verify(repository, never()).findByTenantId(any());
    }

    // ==================== validateComplexity ====================

    @Test
    @DisplayName("validateComplexity: 满足所有要求的密码通过校验")
    void validateComplexity_validPassword_passes() {
        PasswordSecurityConfig config = createDefaultConfig();

        assertDoesNotThrow(() -> configService.validateComplexity("Str0ng@Pass", config));
    }

    @Test
    @DisplayName("validateComplexity: 空密码抛出 PASSWORD_TOO_SHORT")
    void validateComplexity_emptyPassword_throws() {
        PasswordSecurityConfig config = createDefaultConfig();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> configService.validateComplexity("", config));
        assertTrue(ex.getMessage().contains("PASSWORD_TOO_SHORT"));
    }

    @Test
    @DisplayName("validateComplexity: null 密码抛出 PASSWORD_TOO_SHORT")
    void validateComplexity_nullPassword_throws() {
        PasswordSecurityConfig config = createDefaultConfig();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> configService.validateComplexity(null, config));
        assertTrue(ex.getMessage().contains("PASSWORD_TOO_SHORT"));
    }

    @Test
    @DisplayName("validateComplexity: 长度不足抛出 PASSWORD_TOO_SHORT")
    void validateComplexity_shortPassword_throws() {
        PasswordSecurityConfig config = createDefaultConfig();
        config.setMinLength(8);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> configService.validateComplexity("Ab1@", config));
        assertTrue(ex.getMessage().contains("PASSWORD_TOO_SHORT"));
        assertTrue(ex.getMessage().contains("8"));
    }

    @Test
    @DisplayName("validateComplexity: 缺少大写字母抛出 COMPLEXITY_INSUFFICIENT")
    void validateComplexity_noUppercase_throws() {
        PasswordSecurityConfig config = createDefaultConfig();
        config.setRequireUppercase(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> configService.validateComplexity("str0ng@pass", config));
        assertTrue(ex.getMessage().contains("PASSWORD_COMPLEXITY_INSUFFICIENT"));
        assertTrue(ex.getMessage().contains("大写字母"));
    }

    @Test
    @DisplayName("validateComplexity: 缺少小写字母抛出 COMPLEXITY_INSUFFICIENT")
    void validateComplexity_noLowercase_throws() {
        PasswordSecurityConfig config = createDefaultConfig();
        config.setRequireLowercase(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> configService.validateComplexity("STR0NG@PASS", config));
        assertTrue(ex.getMessage().contains("PASSWORD_COMPLEXITY_INSUFFICIENT"));
        assertTrue(ex.getMessage().contains("小写字母"));
    }

    @Test
    @DisplayName("validateComplexity: 缺少数字抛出 COMPLEXITY_INSUFFICIENT")
    void validateComplexity_noDigit_throws() {
        PasswordSecurityConfig config = createDefaultConfig();
        config.setRequireDigit(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> configService.validateComplexity("Strong@Pass", config));
        assertTrue(ex.getMessage().contains("PASSWORD_COMPLEXITY_INSUFFICIENT"));
        assertTrue(ex.getMessage().contains("数字"));
    }

    @Test
    @DisplayName("validateComplexity: 缺少特殊字符抛出 COMPLEXITY_INSUFFICIENT")
    void validateComplexity_noSpecialChar_throws() {
        PasswordSecurityConfig config = createDefaultConfig();
        config.setRequireSpecialChar(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> configService.validateComplexity("Str0ngPass", config));
        assertTrue(ex.getMessage().contains("PASSWORD_COMPLEXITY_INSUFFICIENT"));
        assertTrue(ex.getMessage().contains("特殊字符"));
    }

    @Test
    @DisplayName("validateComplexity: 关闭所有复杂度要求后简单密码通过")
    void validateComplexity_allRequirementsOff_simplePasswordPasses() {
        PasswordSecurityConfig config = createDefaultConfig();
        config.setMinLength(4);
        config.setRequireUppercase(false);
        config.setRequireLowercase(false);
        config.setRequireDigit(false);
        config.setRequireSpecialChar(false);

        assertDoesNotThrow(() -> configService.validateComplexity("abcd", config));
    }

    // ==================== updateConfig ====================

    @Test
    @DisplayName("updateConfig: 新配置创建并保存")
    void updateConfig_newConfig_creates() {
        PasswordSecurityConfig newConfig = new PasswordSecurityConfig();
        newConfig.setTenantId(TENANT_ID);
        newConfig.setMinLength(10);
        newConfig.setMaxFailedAttempts(3);

        when(repository.findByTenantId(TENANT_ID)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            PasswordSecurityConfig saved = inv.getArgument(0);
            saved.setId(1L);
            return saved;
        });

        PasswordSecurityConfig result = configService.updateConfig(newConfig);

        assertNotNull(result.getId());
        assertEquals(TENANT_ID, result.getTenantId());
        assertEquals(10, result.getMinLength());
        assertEquals(3, result.getMaxFailedAttempts());
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());
    }

    @Test
    @DisplayName("updateConfig: 已有配置更新而非创建")
    void updateConfig_existingConfig_updates() {
        PasswordSecurityConfig existing = new PasswordSecurityConfig();
        existing.setId(1L);
        existing.setTenantId(TENANT_ID);
        existing.setMinLength(8);
        existing.setMaxFailedAttempts(5);
        existing.setVersion(2L);

        PasswordSecurityConfig newConfig = new PasswordSecurityConfig();
        newConfig.setTenantId(TENANT_ID);
        newConfig.setMinLength(12);
        newConfig.setMaxFailedAttempts(3);

        when(repository.findByTenantId(TENANT_ID)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PasswordSecurityConfig result = configService.updateConfig(newConfig);

        assertEquals(1L, result.getId());
        assertEquals(12, result.getMinLength());
        assertEquals(3, result.getMaxFailedAttempts());
        assertEquals(2L, result.getVersion()); // version 保留
        assertNotNull(result.getUpdatedAt());
    }

    @Test
    @DisplayName("updateConfig: tenantId 为 null 时抛出异常")
    void updateConfig_nullTenantId_throws() {
        PasswordSecurityConfig config = new PasswordSecurityConfig();
        config.setTenantId(null);

        assertThrows(IllegalArgumentException.class, () -> configService.updateConfig(config));
    }

    @Test
    @DisplayName("updateConfig: tenantId 为空字符串时抛出异常")
    void updateConfig_blankTenantId_throws() {
        PasswordSecurityConfig config = new PasswordSecurityConfig();
        config.setTenantId("");

        assertThrows(IllegalArgumentException.class, () -> configService.updateConfig(config));
    }

    // ==================== 辅助方法 ====================

    private PasswordSecurityConfig createDefaultConfig() {
        PasswordSecurityConfig config = new PasswordSecurityConfig();
        config.setTenantId(TENANT_ID);
        config.setMinLength(8);
        config.setRequireUppercase(true);
        config.setRequireLowercase(true);
        config.setRequireDigit(true);
        config.setRequireSpecialChar(true);
        config.setMaxFailedAttempts(5);
        config.setLockDurationMinutes(30);
        config.setPasswordExpiryDays(90);
        config.setPasswordHistoryCount(5);
        return config;
    }
}