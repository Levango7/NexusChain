package org.nexus.gateway.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 审计落库门禁测试（P0 修复 2026-10-11）。
 *
 * <p>此前审计只写 {@code AUDIT} logger（logback → audit.json 文件）：不落库、不可查询。
 * 本测试钉住：① 事件落到 {@code audit_events} 表；② 落库失败不阻断业务但显式报错；
 * ③ 无仓储时仅写日志（兼容旧装配）。</p>
 */
class AuditLogServicePersistTest {

    private AuditEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
    }

    private AuditEvent event() {
        AuditEvent e = new AuditEvent();
        e.setActor("merchant:700");
        e.setAction("PAYMENT_CONFIRMED");
        e.setResource("order:ORD-001");
        e.setMerchantId(700L);
        e.setDetail("confirm");
        e.setIpAddress("127.0.0.1");
        return e;
    }

    @Test
    @DisplayName("record → 同时落库（此前只有日志文件）")
    void record_persistsToDatabase() {
        when(repository.save(any(AuditEventEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        AuditLogService service = new AuditLogService(repository);

        service.record(event());

        ArgumentCaptor<AuditEventEntity> captor = ArgumentCaptor.forClass(AuditEventEntity.class);
        verify(repository).save(captor.capture());
        AuditEventEntity saved = captor.getValue();
        assertEquals("PAYMENT_CONFIRMED", saved.getAction());
        assertEquals("order:ORD-001", saved.getResource());
        assertEquals(700L, saved.getMerchantId());
        assertNotNull(saved.getEventTime());
    }

    @Test
    @DisplayName("落库失败 → 不阻断业务（异常被吞），但不得假装成功")
    void record_persistFailure_doesNotBreakBusiness() {
        when(repository.save(any(AuditEventEntity.class)))
                .thenThrow(new RuntimeException("db down"));
        AuditLogService service = new AuditLogService(repository);

        // 不得抛异常（审计是旁路）
        assertDoesNotThrow(() -> service.record(event()));
        // 但必须尝试过落库
        verify(repository).save(any(AuditEventEntity.class));
    }

    @Test
    @DisplayName("无仓储（旧装配）→ 仅写日志，不抛异常")
    void record_withoutRepository_logsOnly() {
        AuditLogService service = new AuditLogService();
        assertDoesNotThrow(() -> service.record(event()));
    }
}
