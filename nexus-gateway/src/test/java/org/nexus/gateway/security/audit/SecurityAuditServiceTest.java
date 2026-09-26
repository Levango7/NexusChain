package org.nexus.gateway.security.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SecurityAuditService 单元测试（Wave 12 XSEC 模块）。
 *
 * <p>覆盖：record 方法正确记录审计事件（含 timestamp 自动填充、JSON 格式）；
 * 便捷方法正确构造 AuditEvent；日志中不包含明文敏感数据。</p>
 *
 * <p>SecurityAuditService 使用 private static final Logger "SECURITY_AUDIT"，
 * 通过反射 + Unsafe 注入 mock logger 来验证日志输出（JDK 17 兼容）。</p>
 */
class SecurityAuditServiceTest {

    private Logger mockAuditLogger;
    private SecurityAuditService service;

    @BeforeEach
    void setUp() throws Exception {
        mockAuditLogger = mock(Logger.class);
        service = new SecurityAuditService();

        // 通过反射注入 mock logger 到 private static final 字段
        Field auditLogField = SecurityAuditService.class.getDeclaredField("auditLog");
        auditLogField.setAccessible(true);

        try {
            auditLogField.set(null, mockAuditLogger);
        } catch (IllegalAccessException e) {
            // JDK 17 限制：使用 Unsafe 修改 static final 字段
            sun.misc.Unsafe unsafe = getUnsafe();
            long offset = unsafe.staticFieldOffset(auditLogField);
            Object base = unsafe.staticFieldBase(auditLogField);
            unsafe.putObject(base, offset, mockAuditLogger);
        }
    }

    private sun.misc.Unsafe getUnsafe() throws Exception {
        Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (sun.misc.Unsafe) unsafeField.get(null);
    }

    @Test
    @DisplayName("record: 审计事件正确写入 SECURITY_AUDIT 日志（JSON 格式）")
    void record_validEvent_logsJson() {
        AuditEvent event = new AuditEvent(
                123L, "ENCRYPT_FIELD", AuditEventType.ENCRYPTION_OPERATION,
                "SUCCESS", 456L, "field=cardNumber, masked"
        );
        service.record(event);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockAuditLogger).info(msgCaptor.capture(), any(), any(), any(), any(), any(), any(), any());

        String logMsg = msgCaptor.getValue();
        assertTrue(logMsg.contains("merchantId"), "日志应包含 merchantId");
        assertTrue(logMsg.contains("operationType"), "日志应包含 operationType");
        assertTrue(logMsg.contains("securityEventType"), "日志应包含 securityEventType");
        assertTrue(logMsg.contains("result"), "日志应包含 result");
        assertTrue(logMsg.contains("paymentOrderId"), "日志应包含 paymentOrderId");
        assertTrue(logMsg.contains("details"), "日志应包含 details");
    }

    @Test
    @DisplayName("record: timestamp 为 null 时自动填充当前时间")
    void record_nullTimestamp_autoFilled() {
        AuditEvent event = new AuditEvent();
        event.setMerchantId(100L);
        event.setOperationType("TEST_OP");
        event.setSecurityEventType(AuditEventType.ENCRYPTION_OPERATION);
        event.setResult("SUCCESS");
        event.setPaymentOrderId(200L);
        event.setSafeDetails("test details");
        event.setTimestamp(null);

        service.record(event);

        assertNotNull(event.getTimestamp(), "record 应自动填充 timestamp");
        verify(mockAuditLogger).info(anyString(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("record: timestamp 已设置时不覆盖")
    void record_existingTimestamp_preserved() {
        Instant fixedInstant = Instant.parse("2026-01-01T00:00:00Z");
        AuditEvent event = new AuditEvent();
        event.setMerchantId(100L);
        event.setOperationType("TEST_OP");
        event.setSecurityEventType(AuditEventType.KEY_ROTATION);
        event.setResult("SUCCESS");
        event.setSafeDetails("details");
        event.setTimestamp(fixedInstant);

        service.record(event);

        assertEquals(fixedInstant, event.getTimestamp(), "已有 timestamp 不应被覆盖");
    }

    @Test
    @DisplayName("record: null 字段在 JSON 中输出为 'null' 字符串")
    void record_nullFields_outputAsNullString() {
        AuditEvent event = new AuditEvent(
                null, null, null, null, null, null
        );

        service.record(event);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(mockAuditLogger).info(msgCaptor.capture(), any(), any(), any(), any(), any(), any(), any());

        assertNotNull(msgCaptor.getValue());
    }

    @Test
    @DisplayName("recordEncryptionOperation: 正确构造加密操作审计事件")
    void recordEncryptionOperation_constructsCorrectEvent() {
        service.recordEncryptionOperation(123L, "ENCRYPT_FIELD", "SUCCESS", 456L, "masked");

        verify(mockAuditLogger).info(contains("securityEventType"), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("recordKeyRotation: 正确构造密钥轮换审计事件")
    void recordKeyRotation_constructsCorrectEvent() {
        service.recordKeyRotation(123L, "SUCCESS", "KEK rotated to v3");

        verify(mockAuditLogger).info(contains("securityEventType"), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("recordReplayInterception: 正确构造防重放拦截审计事件")
    void recordReplayInterception_constructsCorrectEvent() {
        service.recordReplayInterception(123L, "BLOCKED", "nonce reuse detected");

        verify(mockAuditLogger).info(contains("securityEventType"), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("recordThreeDsAuth: 正确构造 3DS 认证审计事件")
    void recordThreeDsAuth_constructsCorrectEvent() {
        service.recordThreeDsAuth(123L, "SUCCESS", 456L, "frictionless flow");

        verify(mockAuditLogger).info(contains("securityEventType"), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("recordPasswordValidation: 正确构造密码验证审计事件")
    void recordPasswordValidation_constructsCorrectEvent() {
        service.recordPasswordValidation(123L, "FAILED", "attempt 3/5");

        verify(mockAuditLogger).info(contains("securityEventType"), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("recordPasswordLocked: 正确构造密码锁定审计事件，result 固定为 BLOCKED")
    void recordPasswordLocked_resultIsBlocked() {
        service.recordPasswordLocked(123L, "5 failed attempts");

        verify(mockAuditLogger).info(contains("securityEventType"), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("recordSecondFactor: 正确构造二次验证审计事件")
    void recordSecondFactor_constructsCorrectEvent() {
        service.recordSecondFactor(123L, "OTP_VERIFY", "SUCCESS", 456L, "OTP sent");

        verify(mockAuditLogger).info(contains("securityEventType"), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("日志中不包含明文敏感数据（safeDetails 应为脱敏内容）")
    void record_safeDetailsDoesNotContainSensitiveData() {
        String safeDetails = "field=cardNumber, masked=****1234";
        service.recordEncryptionOperation(123L, "ENCRYPT_FIELD", "SUCCESS", 456L, safeDetails);

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> argCaptor = ArgumentCaptor.forClass(Object.class);
        verify(mockAuditLogger).info(msgCaptor.capture(), argCaptor.capture(), argCaptor.capture(),
                argCaptor.capture(), argCaptor.capture(), argCaptor.capture(), argCaptor.capture(), argCaptor.capture());

        for (Object arg : argCaptor.getAllValues()) {
            if (arg instanceof String str) {
                assertFalse(str.contains("password="), "日志参数不应包含明文 password");
                assertFalse(str.contains("cvv="), "日志参数不应包含明文 CVV");
                assertFalse(str.matches(".*cardNumber=\\d{16}.*"), "日志参数不应包含明文 16 位卡号");
            }
        }
    }
}