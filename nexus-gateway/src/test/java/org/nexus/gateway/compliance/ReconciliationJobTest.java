package org.nexus.gateway.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.audit.AuditLogService;

import static org.mockito.Mockito.*;

/**
 * {@link ReconciliationJob} 单元测试：验证定时任务入口不抛异常。
 */
class ReconciliationJobTest {

    @Test
    @DisplayName("reconcile: 正常执行不抛异常")
    void reconcile_noException() {
        AuditLogService auditLog = mock(AuditLogService.class);
        ReconciliationJob job = new ReconciliationJob(auditLog);
        job.reconcile();
        // 当前实现 checked=0, discrepancies=0，不调用 auditLog
        verify(auditLog, never()).recordPayment(any(), any(), any(), any());
    }
}