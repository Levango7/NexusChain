package org.nexus.gateway.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.settlement.clearing.ClearingEngine;
import org.nexus.settlement.reconciliation.DiscrepancyDetail;
import org.nexus.settlement.reconciliation.ReconciliationReport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

/**
 * {@link ReconciliationScheduler} 单元测试。
 *
 * <p>覆盖：正常对账、开关关闭跳过、reconcile 异常不外抛（调度线程安全）、
 * null 报告安全、差错报告输出。</p>
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationSchedulerTest {

    @Mock
    private ClearingEngine clearingEngine;

    private ReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ReconciliationScheduler(clearingEngine, true);
    }

    private ReconciliationReport cleanReport() {
        ReconciliationReport r = new ReconciliationReport();
        r.setSource("CHAIN");
        r.setReconcileDate(LocalDate.now());
        r.setMatchedCount(3);
        r.setDiscrepancyCount(0);
        return r;
    }

    private ReconciliationReport discrepancyReport() {
        ReconciliationReport r = new ReconciliationReport();
        r.setSource("CHAIN");
        r.setReconcileDate(LocalDate.now());
        r.setMatchedCount(1);
        r.setDiscrepancyCount(1);
        r.setTotalLocal(2);
        r.setTotalExternal(1);
        r.setTotalDiscrepancyAmount(new BigDecimal("100"));
        r.setDetails(List.of(new DiscrepancyDetail(
                DiscrepancyDetail.Type.LOCAL_ONLY, "O1", new BigDecimal("100"), null)));
        return r;
    }

    @Test
    void reconcile_cleanReport_shouldCompleteQuietly() {
        when(clearingEngine.reconcile(any())).thenReturn(cleanReport());

        assertDoesNotThrow(() -> scheduler.reconcile());

        verify(clearingEngine).reconcile(any());
    }

    @Test
    void reconcile_discrepancyReport_shouldCompleteQuietly() {
        when(clearingEngine.reconcile(any())).thenReturn(discrepancyReport());

        assertDoesNotThrow(() -> scheduler.reconcile());

        verify(clearingEngine).reconcile(any());
    }

    @Test
    void reconcile_disabled_shouldSkipEverything() {
        ReconciliationScheduler disabled = new ReconciliationScheduler(clearingEngine, false);

        disabled.reconcile();

        verifyNoInteractions(clearingEngine);
    }

    @Test
    void reconcile_engineThrows_shouldNotPropagate() {
        when(clearingEngine.reconcile(any())).thenThrow(new RuntimeException("chain unreachable"));

        assertDoesNotThrow(() -> scheduler.reconcile());
    }

    @Test
    void reconcile_nullReport_shouldSkip() {
        when(clearingEngine.reconcile(any())).thenReturn(null);

        assertDoesNotThrow(() -> scheduler.reconcile());

        verify(clearingEngine).reconcile(any());
    }
}
