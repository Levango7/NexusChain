package org.nexus.gateway.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.gateway.security.audit.AuditEventType;
import org.nexus.gateway.security.audit.SecurityAuditService;
import org.nexus.gateway.security.audit.SecurityAuditSink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 安全审计落库映射测试（#52 A 段）。
 *
 * <p>此前 {@code SecurityAuditService.record()} 只往一个没有 appender 的 logger 打一行 JSON ⇒ 安全
 * 事件在库里一条都查不到。本类钉住 {@link AuditStoreSecuritySink} 的映射：类别编进 {@code action}
 * 前缀、{@code actor=system}、资源优先用订单号、无商户无订单时退化成事件名，以及
 * **写前按 V96 列宽截断**。</p>
 *
 * <p>截断为什么要专门测：{@code AuditLogService.persist} 对插入失败是"捕获后只记
 * {@code AUDIT_PERSIST_FAILED}"的旁路——真超长时的表现是"看着写进去了其实没有"，
 * 门禁与日志都不会红，只能在这里挡。</p>
 */
class AuditStoreSecuritySinkTest {

    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AuditStoreSecuritySink sink = new AuditStoreSecuritySink(auditLogService);

    @Test
    @DisplayName("3DS 事件映射：action 带 SECURITY_ 前缀、resource 用订单号、actor=system")
    void threeDsAuthMapsToAuditRow() {
        sink.persist(new org.nexus.gateway.security.audit.AuditEvent(
                1001L, "3DS_AUTH", AuditEventType.THREE_DS_AUTH, "N", 55L,
                "authStatus=TIMEOUT, riskScore=42"));

        AuditEvent row = capture();
        assertEquals("system", row.getActor(), "安全观测事件的 actor 是 system（V96 注释里就列了这一档）");
        assertEquals("SECURITY_THREE_DS_AUTH", row.getAction());
        assertEquals("paymentOrder:55", row.getResource());
        assertEquals(1001L, row.getMerchantId().longValue());
        assertTrue(row.getDetail().contains("op=3DS_AUTH"), row.getDetail());
        assertTrue(row.getDetail().contains("result=N"), row.getDetail());
        assertTrue(row.getDetail().contains("riskScore=42"), row.getDetail());
    }

    @Test
    @DisplayName("密码锁定事件按类别映射，供按 action 前缀检索")
    void passwordLockMapsToItsOwnAction() {
        sink.persist(new org.nexus.gateway.security.audit.AuditEvent(
                7L, "PASSWORD_LOCKED", AuditEventType.PASSWORD_LOCKED, "LOCKED", null,
                "连续失败 5/5 次触发自动锁定"));

        AuditEvent row = capture();
        assertEquals("SECURITY_PASSWORD_LOCKED", row.getAction());
        assertEquals("merchant:7", row.getResource(), "无订单号时退化成商户资源，仍可按 merchant_id 查");
        assertEquals(7L, row.getMerchantId().longValue());
    }

    @Test
    @DisplayName("KEK 轮换是全局事件：merchantId 允许为 null，资源退化成事件名")
    void keyRotationIsGlobalAndKeepsNullMerchant() {
        sink.persist(new org.nexus.gateway.security.audit.AuditEvent(
                null, "KEY_ROTATION", AuditEventType.KEY_ROTATION, "KEY_STILL_IN_USE", null,
                "KEK v1 仍有 3 条 DEK 引用，未归档"));

        AuditEvent row = capture();
        assertEquals("SECURITY_KEY_ROTATION", row.getAction());
        assertNull(row.getMerchantId(), "轮换不隶属单个商户；null 也让频次异常检测自然跳过");
        assertEquals("security-event:KEY_ROTATION", row.getResource());
        assertTrue(row.getDetail().contains("result=KEY_STILL_IN_USE"), row.getDetail());
    }

    @Test
    @DisplayName("超长字段必须在写入前截断（否则表现为静默丢行，日志与门禁都不会红）")
    void oversizedFieldsAreTruncatedToColumnWidths() {
        sink.persist(new org.nexus.gateway.security.audit.AuditEvent(
                1L, "3DS_AUTH", AuditEventType.THREE_DS_AUTH, "Y", 900L, "x".repeat(5000)));

        AuditEvent row = capture();
        assertTrue(row.getDetail().length() <= 1024,
                "detail 超出 V96 的 VARCHAR(1024) 会插入失败：len=" + row.getDetail().length());
        assertTrue(row.getAction().length() <= 64, "action 列宽 64");
        assertTrue(row.getResource().length() <= 256, "resource 列宽 256");
    }

    @Test
    @DisplayName("SecurityAuditService 只做日志 + 移交 sink；无 sink 时不抛（旧装配）")
    void serviceDelegatesAndToleratesMissingSink() {
        SecurityAuditSink spy = mock(SecurityAuditSink.class);
        new SecurityAuditService(spy).recordThreeDsAuth(3L, "Y", 42L, "detail");
        verify(spy).persist(org.mockito.ArgumentMatchers.argThat(e ->
                Long.valueOf(3L).equals(e.getMerchantId())
                        && Long.valueOf(42L).equals(e.getPaymentOrderId())
                        && "Y".equals(e.getResult())
                        && e.getSecurityEventType() == AuditEventType.THREE_DS_AUTH));

        // 无 sink 构造：只走日志，不得抛异常（这是既有调用方的装配形状）
        new SecurityAuditService().recordPasswordLocked(9L, "detail");
    }

    private AuditEvent capture() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogService).record(captor.capture());
        return captor.getValue();
    }
}
