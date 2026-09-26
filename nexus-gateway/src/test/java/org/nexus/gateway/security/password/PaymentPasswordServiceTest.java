package org.nexus.gateway.security.password;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link PaymentPasswordService} 单元测试。
 *
 * <p>覆盖支付密码的设置、验证、更换、解锁、锁定、过期等核心流程。
 * 使用真实 BCryptPasswordEncoder 确保 bcrypt 哈希/验证逻辑正确，
 * mock 所有 Repository 和协作 Service。</p>
 *
 * <p>经验来源：2026-09-25-fintech-payment-unit-test-coverage-matrix（bcrypt 哈希验证）；
 * 2026-09-21-autowired-field-injection-test-fix（构造器注入 mock 模式）。</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentPasswordServiceTest {

    private static final Long MERCHANT_ID = 1001L;
    private static final String TENANT_ID = "tenant-001";
    private static final String VALID_PASSWORD = "Str0ng@Pass";
    private static final String WRONG_PASSWORD = "Wr0ng@Pass";

    @Mock
    private MerchantPaymentPasswordRepository passwordRepository;

    @Mock
    private PasswordSecurityConfigService configService;

    @Mock
    private PasswordHistoryService historyService;

    @InjectMocks
    private PaymentPasswordService paymentPasswordService;

    private BCryptPasswordEncoder realEncoder;
    private PasswordSecurityConfig defaultConfig;

    @BeforeEach
    void setUp() {
        realEncoder = new BCryptPasswordEncoder(10);

        defaultConfig = new PasswordSecurityConfig();
        defaultConfig.setTenantId(TENANT_ID);
        defaultConfig.setMinLength(8);
        defaultConfig.setRequireUppercase(true);
        defaultConfig.setRequireLowercase(true);
        defaultConfig.setRequireDigit(true);
        defaultConfig.setRequireSpecialChar(true);
        defaultConfig.setMaxFailedAttempts(5);
        defaultConfig.setLockDurationMinutes(30);
        defaultConfig.setPasswordExpiryDays(90);
        defaultConfig.setPasswordHistoryCount(5);
    }

    // ==================== setPassword ====================

    @Test
    @DisplayName("setPassword: 首次设置密码成功，bcrypt 哈希存储")
    void setPassword_firstTime_success() {
        when(configService.getConfig(TENANT_ID)).thenReturn(defaultConfig);
        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());
        when(passwordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        paymentPasswordService.setPassword(MERCHANT_ID, VALID_PASSWORD, TENANT_ID);

        ArgumentCaptor<MerchantPaymentPassword> captor = ArgumentCaptor.forClass(MerchantPaymentPassword.class);
        verify(passwordRepository).save(captor.capture());

        MerchantPaymentPassword saved = captor.getValue();
        assertEquals(MERCHANT_ID, saved.getMerchantId());
        assertNotNull(saved.getPasswordHash());
        assertTrue(saved.getPasswordHash().startsWith("$2a$10$"));
        assertEquals(PasswordStatus.ACTIVE, saved.getStatus());
        assertEquals(0, saved.getFailedAttempts());
        assertNull(saved.getLockedUntil());
        assertNotNull(saved.getLastChangedAt());
        assertNotNull(saved.getCreatedAt());

        // 验证 bcrypt 哈希可以正确匹配明文密码
        assertTrue(realEncoder.matches(VALID_PASSWORD, saved.getPasswordHash()));

        // 验证记录了密码历史
        verify(historyService).recordPassword(eq(MERCHANT_ID), anyString());
    }

    @Test
    @DisplayName("setPassword: 已有密码时覆盖更新")
    void setPassword_existingPassword_overwrite() {
        MerchantPaymentPassword existing = new MerchantPaymentPassword();
        existing.setId(1L);
        existing.setMerchantId(MERCHANT_ID);
        existing.setPasswordHash(realEncoder.encode("OldP@ss1"));
        existing.setStatus(PasswordStatus.LOCKED);
        existing.setFailedAttempts(3);
        existing.setLockedUntil(Instant.now().plus(10, ChronoUnit.MINUTES));
        existing.setLastChangedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        existing.setCreatedAt(Instant.now().minus(60, ChronoUnit.DAYS));

        when(configService.getConfig(TENANT_ID)).thenReturn(defaultConfig);
        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(existing));
        when(passwordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        paymentPasswordService.setPassword(MERCHANT_ID, VALID_PASSWORD, TENANT_ID);

        ArgumentCaptor<MerchantPaymentPassword> captor = ArgumentCaptor.forClass(MerchantPaymentPassword.class);
        verify(passwordRepository).save(captor.capture());

        MerchantPaymentPassword saved = captor.getValue();
        // 覆盖更新后状态恢复
        assertEquals(PasswordStatus.ACTIVE, saved.getStatus());
        assertEquals(0, saved.getFailedAttempts());
        assertNull(saved.getLockedUntil());
        assertNotNull(saved.getLastChangedAt());
        assertTrue(realEncoder.matches(VALID_PASSWORD, saved.getPasswordHash()));

        verify(historyService).recordPassword(eq(MERCHANT_ID), anyString());
    }

    @Test
    @DisplayName("setPassword: 密码复杂度不足时抛出 IllegalArgumentException")
    void setPassword_weakPassword_throws() {
        when(configService.getConfig(TENANT_ID)).thenReturn(defaultConfig);
        doThrow(new IllegalArgumentException("PASSWORD_TOO_SHORT: 密码长度不足"))
                .when(configService).validateComplexity("weak", defaultConfig);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> paymentPasswordService.setPassword(MERCHANT_ID, "weak", TENANT_ID));

        assertTrue(ex.getMessage().contains("PASSWORD_TOO_SHORT"));
        verify(passwordRepository, never()).save(any());
        verify(historyService, never()).recordPassword(anyLong(), anyString());
    }

    // ==================== verifyPassword ====================

    @Test
    @DisplayName("verifyPassword: 未设置密码时返回 SKIPPED")
    void verifyPassword_noPasswordSet_returnsSkipped() {
        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        PaymentPasswordService.PasswordVerifyResult result =
                paymentPasswordService.verifyPassword(MERCHANT_ID, VALID_PASSWORD, TENANT_ID);

        assertEquals(PaymentPasswordService.PasswordVerifyResult.Status.SKIPPED, result.getStatus());
        verify(configService, never()).getConfig(any());
    }

    @Test
    @DisplayName("verifyPassword: 正确密码返回 SUCCESS 并重置失败计数")
    void verifyPassword_correctPassword_returnsSuccess() {
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));
        entity.setFailedAttempts(2);

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));
        when(configService.getConfig(TENANT_ID)).thenReturn(defaultConfig);
        when(passwordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentPasswordService.PasswordVerifyResult result =
                paymentPasswordService.verifyPassword(MERCHANT_ID, VALID_PASSWORD, TENANT_ID);

        assertEquals(PaymentPasswordService.PasswordVerifyResult.Status.SUCCESS, result.getStatus());
        assertEquals(0, entity.getFailedAttempts());
        assertEquals(PasswordStatus.ACTIVE, entity.getStatus());
        assertNull(entity.getLockedUntil());
    }

    @Test
    @DisplayName("verifyPassword: 错误密码返回 INVALID 并递增失败计数")
    void verifyPassword_wrongPassword_returnsInvalid() {
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));
        entity.setFailedAttempts(1);

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));
        when(configService.getConfig(TENANT_ID)).thenReturn(defaultConfig);
        when(passwordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentPasswordService.PasswordVerifyResult result =
                paymentPasswordService.verifyPassword(MERCHANT_ID, WRONG_PASSWORD, TENANT_ID);

        assertEquals(PaymentPasswordService.PasswordVerifyResult.Status.INVALID, result.getStatus());
        assertEquals(2, entity.getFailedAttempts());
        // remaining = maxFailedAttempts - currentFailedAttempts = 5 - 2 = 3
        assertEquals(3, result.getRemainingAttempts());
    }

    @Test
    @DisplayName("verifyPassword: 达到失败上限后锁定返回 LOCKED")
    void verifyPassword_maxFailedAttempts_locksAccount() {
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));
        entity.setFailedAttempts(4); // 第5次失败将触发锁定

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));
        when(configService.getConfig(TENANT_ID)).thenReturn(defaultConfig);
        when(passwordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentPasswordService.PasswordVerifyResult result =
                paymentPasswordService.verifyPassword(MERCHANT_ID, WRONG_PASSWORD, TENANT_ID);

        assertEquals(PaymentPasswordService.PasswordVerifyResult.Status.LOCKED, result.getStatus());
        assertEquals(5, entity.getFailedAttempts());
        assertEquals(PasswordStatus.LOCKED, entity.getStatus());
        assertNotNull(entity.getLockedUntil());
        // 锁定时间应为当前时间 + 30分钟
        assertTrue(entity.getLockedUntil().isAfter(Instant.now()));
        assertTrue(entity.getLockedUntil().isBefore(Instant.now().plus(31, ChronoUnit.MINUTES)));
        assertNotNull(result.getLockedUntil());
    }

    @Test
    @DisplayName("verifyPassword: 锁定中返回 LOCKED 不执行密码验证")
    void verifyPassword_locked_returnsLocked() {
        Instant lockedUntil = Instant.now().plus(20, ChronoUnit.MINUTES);
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));
        entity.setStatus(PasswordStatus.LOCKED);
        entity.setLockedUntil(lockedUntil);

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));

        PaymentPasswordService.PasswordVerifyResult result =
                paymentPasswordService.verifyPassword(MERCHANT_ID, VALID_PASSWORD, TENANT_ID);

        assertEquals(PaymentPasswordService.PasswordVerifyResult.Status.LOCKED, result.getStatus());
        assertEquals(lockedUntil, result.getLockedUntil());
        // 锁定状态下不应调用 configService 或执行密码验证
        verify(configService, never()).getConfig(any());
        verify(passwordRepository, never()).save(any());
    }

    @Test
    @DisplayName("verifyPassword: 密码过期返回 EXPIRED")
    void verifyPassword_expired_returnsExpired() {
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));
        // lastChangedAt 设为 100 天前，超过 90 天过期阈值
        entity.setLastChangedAt(Instant.now().minus(100, ChronoUnit.DAYS));

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));
        when(configService.getConfig(TENANT_ID)).thenReturn(defaultConfig);
        when(passwordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentPasswordService.PasswordVerifyResult result =
                paymentPasswordService.verifyPassword(MERCHANT_ID, VALID_PASSWORD, TENANT_ID);

        assertEquals(PaymentPasswordService.PasswordVerifyResult.Status.EXPIRED, result.getStatus());
        assertEquals(PasswordStatus.EXPIRED, entity.getStatus());
    }

    // ==================== changePassword ====================

    @Test
    @DisplayName("changePassword: 旧密码正确时成功更换")
    void changePassword_correctOldPassword_success() {
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));
        when(configService.getConfig(TENANT_ID)).thenReturn(defaultConfig);
        when(historyService.isPlaintextInHistory(MERCHANT_ID, "NewStr0ng@Pass", 5)).thenReturn(false);
        when(passwordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        paymentPasswordService.changePassword(MERCHANT_ID, VALID_PASSWORD, "NewStr0ng@Pass", TENANT_ID);

        assertTrue(realEncoder.matches("NewStr0ng@Pass", entity.getPasswordHash()));
        assertEquals(PasswordStatus.ACTIVE, entity.getStatus());
        assertEquals(0, entity.getFailedAttempts());
        assertNull(entity.getLockedUntil());
        verify(historyService).recordPassword(eq(MERCHANT_ID), anyString());
    }

    @Test
    @DisplayName("changePassword: 旧密码错误时抛出 OLD_PASSWORD_INVALID")
    void changePassword_wrongOldPassword_throws() {
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> paymentPasswordService.changePassword(MERCHANT_ID, WRONG_PASSWORD, "NewStr0ng@Pass", TENANT_ID));

        assertTrue(ex.getMessage().contains("OLD_PASSWORD_INVALID"));
        verify(configService, never()).getConfig(any());
        verify(historyService, never()).recordPassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("changePassword: 新密码与历史重复时抛出 PASSWORD_REUSED")
    void changePassword_reusedPassword_throws() {
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));
        when(configService.getConfig(TENANT_ID)).thenReturn(defaultConfig);
        when(historyService.isPlaintextInHistory(MERCHANT_ID, "NewStr0ng@Pass", 5)).thenReturn(true);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> paymentPasswordService.changePassword(MERCHANT_ID, VALID_PASSWORD, "NewStr0ng@Pass", TENANT_ID));

        assertTrue(ex.getMessage().contains("PASSWORD_REUSED"));
        verify(passwordRepository, never()).save(any());
    }

    @Test
    @DisplayName("changePassword: 未设置密码时抛出异常")
    void changePassword_noPasswordSet_throws() {
        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> paymentPasswordService.changePassword(MERCHANT_ID, VALID_PASSWORD, "NewStr0ng@Pass", TENANT_ID));
    }

    // ==================== unlockPassword ====================

    @Test
    @DisplayName("unlockPassword: 解锁后状态恢复为 ACTIVE")
    void unlockPassword_success() {
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));
        entity.setStatus(PasswordStatus.LOCKED);
        entity.setFailedAttempts(5);
        entity.setLockedUntil(Instant.now().plus(30, ChronoUnit.MINUTES));

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));
        when(passwordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        paymentPasswordService.unlockPassword(MERCHANT_ID);

        assertEquals(PasswordStatus.ACTIVE, entity.getStatus());
        assertEquals(0, entity.getFailedAttempts());
        assertNull(entity.getLockedUntil());
    }

    @Test
    @DisplayName("unlockPassword: 未设置密码时抛出异常")
    void unlockPassword_noPasswordSet_throws() {
        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> paymentPasswordService.unlockPassword(MERCHANT_ID));
    }

    // ==================== isLocked / isPasswordSet ====================

    @Test
    @DisplayName("isLocked: 锁定状态返回 true")
    void isLocked_locked_returnsTrue() {
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));
        entity.setStatus(PasswordStatus.LOCKED);

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));

        assertTrue(paymentPasswordService.isLocked(MERCHANT_ID));
    }

    @Test
    @DisplayName("isLocked: lockedUntil 未到期返回 true")
    void isLocked_lockedUntilNotExpired_returnsTrue() {
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));
        entity.setLockedUntil(Instant.now().plus(10, ChronoUnit.MINUTES));

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));

        assertTrue(paymentPasswordService.isLocked(MERCHANT_ID));
    }

    @Test
    @DisplayName("isLocked: 正常状态返回 false")
    void isLocked_active_returnsFalse() {
        MerchantPaymentPassword entity = createActiveEntity(realEncoder.encode(VALID_PASSWORD));

        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(entity));

        assertFalse(paymentPasswordService.isLocked(MERCHANT_ID));
    }

    @Test
    @DisplayName("isLocked: 无密码记录返回 false")
    void isLocked_noRecord_returnsFalse() {
        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertFalse(paymentPasswordService.isLocked(MERCHANT_ID));
    }

    @Test
    @DisplayName("isPasswordSet: 有记录返回 true")
    void isPasswordSet_hasRecord_returnsTrue() {
        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(
                Optional.of(createActiveEntity(realEncoder.encode(VALID_PASSWORD))));

        assertTrue(paymentPasswordService.isPasswordSet(MERCHANT_ID));
    }

    @Test
    @DisplayName("isPasswordSet: 无记录返回 false")
    void isPasswordSet_noRecord_returnsFalse() {
        when(passwordRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.empty());

        assertFalse(paymentPasswordService.isPasswordSet(MERCHANT_ID));
    }

    // ==================== 辅助方法 ====================

    private MerchantPaymentPassword createActiveEntity(String passwordHash) {
        MerchantPaymentPassword entity = new MerchantPaymentPassword();
        entity.setId(1L);
        entity.setMerchantId(MERCHANT_ID);
        entity.setPasswordHash(passwordHash);
        entity.setBcryptCost(10);
        entity.setStatus(PasswordStatus.ACTIVE);
        entity.setFailedAttempts(0);
        entity.setLockedUntil(null);
        entity.setLastChangedAt(Instant.now());
        entity.setCreatedAt(Instant.now().minus(10, ChronoUnit.DAYS));
        return entity;
    }
}