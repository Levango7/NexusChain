package org.nexus.gateway.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link AuditLogService} 单元测试：验证 record / recordPayment / recordKeyAccess
 * 三条入口路径，以及高频异常检测分支。
 */
class AuditLogServiceTest {

    private AuditLogService service;

    @BeforeEach
    void setUp() {
        service = new AuditLogService();
    }

    @Test
    @DisplayName("record: 填充 sequence/timestamp 并写日志")
    void record_populatesSequenceAndTimestamp() {
        AuditEvent event = new AuditEvent();
        event.setActor("merchant:1");
        event.setAction("PAYMENT");
        event.setResource("order:1");
        event.setDetail("initiate");
        event.setIpAddress("127.0.0.1");
        event.setMerchantId(1L);

        service.record(event);
        assertNotNull(event.getSequence());
        assertNotNull(event.getTimestamp());
    }

    @Test
    @DisplayName("recordPayment: 构造事件并落库")
    void recordPayment_buildsAndRecords() {
        service.recordPayment(100L, "ORD-1", "PAYMENT_INIT", "10.0.0.1");
    }

    @Test
    @DisplayName("recordKeyAccess: 构造 KEY_ 前缀事件并落库")
    void recordKeyAccess_buildsAndRecords() {
        service.recordKeyAccess(100L, "SIGN", "10.0.0.1");
    }

    @Test
    @DisplayName("checkAnomaly: 超过阈值触发告警分支（不抛异常）")
    void checkAnomaly_aboveThreshold_emitsAlert() {
        // 触发 >100 次同商户操作，进入告警分支
        for (int i = 0; i < 120; i++) {
            service.recordPayment(200L, "ORD-" + i, "PAYMENT", "10.0.0.2");
        }
    }

    @Test
    @DisplayName("record: merchantId 为 null 时跳过异常检测")
    void record_nullMerchantId_skipsAnomaly() {
        AuditEvent event = new AuditEvent();
        event.setActor("system");
        event.setAction("CONFIG_CHANGE");
        event.setResource("config");
        service.record(event);
    }
}