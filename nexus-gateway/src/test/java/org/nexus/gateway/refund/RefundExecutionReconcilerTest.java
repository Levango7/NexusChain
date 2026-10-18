package org.nexus.gateway.refund;

import org.junit.jupiter.api.Test;
import org.nexus.gateway.audit.AuditLogService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RefundExecutionReconciler} 卡单扫描契约测试（批次⑤ P0 加固，2026-10-18）。
 *
 * <p>契约：</p>
 * <ol>
 *   <li>无超时 EXECUTING 单 → 静默（不写审计、不动任何状态）；</li>
 *   <li>有超时单 → 逐笔告警 + 写审计（orderId/refundNo 可追溯），且<b>不自动改状态</b>；</li>
 *   <li>审计写失败 → 旁路吞掉，不阻断扫描；</li>
 *   <li>审计服务未装配 → 扫描仍可运行（不得因此崩溃）。</li>
 * </ol>
 */
class RefundExecutionReconcilerTest {

    private final RefundRequestRepository repository = mock(RefundRequestRepository.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final RefundExecutionReconciler reconciler = new RefundExecutionReconciler(repository);

    private RefundRequest stuck(Long id, String refundNo, Long orderId) {
        RefundRequest r = new RefundRequest();
        r.setId(id);
        r.setRefundNo(refundNo);
        r.setOrderId(orderId);
        r.setMerchantId(100L);
        r.setAmount(new BigDecimal("500"));
        r.setStatus(RefundRequest.RefundStatus.EXECUTING);
        r.setUpdatedAt(LocalDateTime.now().minusMinutes(30));
        return r;
    }

    @Test
    void scan_noStuck_isSilent() {
        when(repository.findByStatusAndUpdatedAtBefore(eq(RefundRequest.RefundStatus.EXECUTING), any()))
                .thenReturn(List.of());
        reconciler.setAuditLogService(auditLogService);

        reconciler.scanStaleExecuting();

        verify(auditLogService, never()).recordRefund(any(), any(), any());
    }

    @Test
    void scan_stuckRefund_alertsAndAuditsWithoutStateChange() {
        RefundRequest stuck = stuck(7L, "RF-7", 1L);
        when(repository.findByStatusAndUpdatedAtBefore(eq(RefundRequest.RefundStatus.EXECUTING), any()))
                .thenReturn(List.of(stuck));
        reconciler.setAuditLogService(auditLogService);

        reconciler.scanStaleExecuting();

        // 逐笔审计：orderId 作为关联键，详情含 refundNo 供人工定位
        verify(auditLogService).recordRefund(eq("1"), eq("REFUND_EXECUTION_STUCK"), contains("RF-7"));
        // 不自动改状态：扫描器不得写库
        verify(repository, never()).save(any());
        verify(repository, never()).markExecuted(any(), any(), any());
        verify(repository, never()).markFailed(any(), any(), any());
    }

    @Test
    void scan_auditWriteFailure_doesNotBreakScan() {
        when(repository.findByStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(stuck(8L, "RF-8", 2L)));
        reconciler.setAuditLogService(auditLogService);
        doThrow(new RuntimeException("audit db down")).when(auditLogService)
                .recordRefund(any(), any(), any());

        assertDoesNotThrow(reconciler::scanStaleExecuting);
    }

    @Test
    void scan_withoutAuditService_stillRuns() {
        when(repository.findByStatusAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(stuck(9L, "RF-9", 3L)));

        assertDoesNotThrow(reconciler::scanStaleExecuting);
    }
}
