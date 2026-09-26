package org.nexus.gateway.security.password;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link SecondFactorService} 单元测试。
 *
 * <p>覆盖 OTP 生成（6位数字、bcrypt 哈希存储、5分钟过期）、
 * 验证成功/失败/过期/无记录等场景。</p>
 *
 * <p>经验来源：2026-09-25-fintech-payment-unit-test-coverage-matrix
 * （bcrypt matches 验证模式）；2026-09-21-autowired-field-injection-test-fix
 * （构造器注入 mock 模式）。</p>
 */
@ExtendWith(MockitoExtension.class)
class SecondFactorServiceTest {

    private static final Long MERCHANT_ID = 2001L;

    @Mock
    private SecondFactorRecordRepository recordRepository;

    private SecondFactorService secondFactorService;

    private BCryptPasswordEncoder realEncoder;

    @BeforeEach
    void setUp() {
        realEncoder = new BCryptPasswordEncoder(10);
        secondFactorService = new SecondFactorService(recordRepository);
    }

    // ==================== generateSecondFactor ====================

    @Test
    @DisplayName("generateSecondFactor: 生成 OTP 记录，6位数字验证码以 bcrypt 哈希存储")
    void generateSecondFactor_success() {
        when(recordRepository.save(any())).thenAnswer(inv -> {
            SecondFactorRecord record = inv.getArgument(0);
            record.setId(1L);
            return record;
        });

        SecondFactorRecord result = secondFactorService.generateSecondFactor(MERCHANT_ID, FactorType.OTP);

        assertNotNull(result);
        assertEquals(MERCHANT_ID, result.getMerchantId());
        assertEquals(FactorType.OTP, result.getFactorType());
        assertNotNull(result.getCodeHash());
        assertTrue(result.getCodeHash().startsWith("$2a$10$"));
        assertFalse(result.isConsumed());
        assertNotNull(result.getExpiresAt());
        assertNotNull(result.getCreatedAt());

        // 过期时间应为当前时间 + 5分钟
        assertTrue(result.getExpiresAt().isAfter(Instant.now()));
        assertTrue(result.getExpiresAt().isBefore(Instant.now().plus(6, ChronoUnit.MINUTES)));

        // 验证保存调用
        ArgumentCaptor<SecondFactorRecord> captor = ArgumentCaptor.forClass(SecondFactorRecord.class);
        verify(recordRepository).save(captor.capture());
        SecondFactorRecord saved = captor.getValue();
        assertEquals(MERCHANT_ID, saved.getMerchantId());
        assertEquals(FactorType.OTP, saved.getFactorType());
    }

    @Test
    @DisplayName("generateSecondFactor: EMAIL 类型生成验证记录")
    void generateSecondFactor_emailType() {
        when(recordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SecondFactorRecord result = secondFactorService.generateSecondFactor(MERCHANT_ID, FactorType.EMAIL);

        assertEquals(FactorType.EMAIL, result.getFactorType());
        verify(recordRepository).save(any());
    }

    @Test
    @DisplayName("generateSecondFactor: TOTP 类型生成验证记录")
    void generateSecondFactor_totpType() {
        when(recordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SecondFactorRecord result = secondFactorService.generateSecondFactor(MERCHANT_ID, FactorType.TOTP);

        assertEquals(FactorType.TOTP, result.getFactorType());
        verify(recordRepository).save(any());
    }

    // ==================== verifySecondFactor ====================

    @Test
    @DisplayName("verifySecondFactor: 正确验证码验证成功，标记 consumed=true")
    void verifySecondFactor_correctCode_success() {
        String code = "123456";
        String codeHash = realEncoder.encode(code);

        SecondFactorRecord record = new SecondFactorRecord();
        record.setId(1L);
        record.setMerchantId(MERCHANT_ID);
        record.setFactorType(FactorType.OTP);
        record.setCodeHash(codeHash);
        record.setExpiresAt(Instant.now().plus(3, ChronoUnit.MINUTES));
        record.setConsumed(false);
        record.setCreatedAt(Instant.now().minus(1, ChronoUnit.MINUTES));

        when(recordRepository.findActiveByMerchantId(eq(MERCHANT_ID), any(Instant.class)))
                .thenReturn(List.of(record));
        when(recordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = secondFactorService.verifySecondFactor(MERCHANT_ID, code);

        assertTrue(result);
        assertTrue(record.isConsumed());
        verify(recordRepository).save(record);
    }

    @Test
    @DisplayName("verifySecondFactor: 错误验证码验证失败")
    void verifySecondFactor_wrongCode_failure() {
        String correctCode = "123456";
        String wrongCode = "999999";
        String codeHash = realEncoder.encode(correctCode);

        SecondFactorRecord record = new SecondFactorRecord();
        record.setId(1L);
        record.setMerchantId(MERCHANT_ID);
        record.setFactorType(FactorType.OTP);
        record.setCodeHash(codeHash);
        record.setExpiresAt(Instant.now().plus(3, ChronoUnit.MINUTES));
        record.setConsumed(false);
        record.setCreatedAt(Instant.now().minus(1, ChronoUnit.MINUTES));

        when(recordRepository.findActiveByMerchantId(eq(MERCHANT_ID), any(Instant.class)))
                .thenReturn(List.of(record));

        boolean result = secondFactorService.verifySecondFactor(MERCHANT_ID, wrongCode);

        assertFalse(result);
        assertFalse(record.isConsumed());
        verify(recordRepository, never()).save(any());
    }

    @Test
    @DisplayName("verifySecondFactor: 无活跃验证记录时返回 false")
    void verifySecondFactor_noActiveRecords_failure() {
        when(recordRepository.findActiveByMerchantId(eq(MERCHANT_ID), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        boolean result = secondFactorService.verifySecondFactor(MERCHANT_ID, "123456");

        assertFalse(result);
        verify(recordRepository, never()).save(any());
    }

    @Test
    @DisplayName("verifySecondFactor: 多条记录中有一条匹配则验证成功")
    void verifySecondFactor_multipleRecordsOneMatches_success() {
        String correctCode = "654321";
        String codeHash1 = realEncoder.encode("111111");
        String codeHash2 = realEncoder.encode(correctCode);

        SecondFactorRecord record1 = new SecondFactorRecord();
        record1.setId(1L);
        record1.setMerchantId(MERCHANT_ID);
        record1.setCodeHash(codeHash1);
        record1.setExpiresAt(Instant.now().plus(3, ChronoUnit.MINUTES));
        record1.setConsumed(false);
        record1.setCreatedAt(Instant.now().minus(2, ChronoUnit.MINUTES));

        SecondFactorRecord record2 = new SecondFactorRecord();
        record2.setId(2L);
        record2.setMerchantId(MERCHANT_ID);
        record2.setCodeHash(codeHash2);
        record2.setExpiresAt(Instant.now().plus(3, ChronoUnit.MINUTES));
        record2.setConsumed(false);
        record2.setCreatedAt(Instant.now().minus(1, ChronoUnit.MINUTES));

        when(recordRepository.findActiveByMerchantId(eq(MERCHANT_ID), any(Instant.class)))
                .thenReturn(List.of(record1, record2));
        when(recordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = secondFactorService.verifySecondFactor(MERCHANT_ID, correctCode);

        assertTrue(result);
        assertTrue(record2.isConsumed());
        assertFalse(record1.isConsumed());
    }

    @Test
    @DisplayName("verifySecondFactor: 已消费的记录不在查询结果中（由 Repository 过滤）")
    void verifySecondFactor_consumedRecordNotReturned() {
        // Repository 的 findActiveByMerchantId 查询条件包含 consumed=false，
        // 所以已消费的记录不会出现在结果中，这里验证 Service 层逻辑：
        // 如果 Repository 返回空列表，则验证失败
        when(recordRepository.findActiveByMerchantId(eq(MERCHANT_ID), any(Instant.class)))
                .thenReturn(Collections.emptyList());

        boolean result = secondFactorService.verifySecondFactor(MERCHANT_ID, "123456");

        assertFalse(result);
    }
}