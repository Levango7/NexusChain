package org.nexus.settlement.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.clearing.Ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultReconciliationService} 单元测试。
 *
 * <p>Path C 扩展：结构化维度（source/双边总量/差错金额汇总）与
 * DiscrepancyDetail 明细断言。</p>
 */
class DefaultReconciliationServiceTest {

    private Ledger ledger;
    private InMemoryChainRecordSource chainSource;
    private InMemoryBankRecordSource bankSource;
    private DefaultReconciliationService service;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        chainSource = new InMemoryChainRecordSource();
        bankSource = new InMemoryBankRecordSource();
        service = new DefaultReconciliationService(ledger, chainSource, bankSource);
    }

    @Test
    void reconcileWithChain_allMatched_shouldBeClean() {
        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");
        ledger.bookSettlement("M001", new BigDecimal("50"), "O2");
        chainSource.feed(List.of(
                record("O1", "100"),
                record("O2", "50")));

        ReconciliationReport report = service.reconcileWithChain();

        assertEquals(2, report.getMatchedCount());
        assertEquals(0, report.getDiscrepancyCount());
        assertTrue(report.getDiscrepancies().isEmpty());
    }

    @Test
    void reconcileWithChain_localOnly_shouldReportDiscrepancy() {
        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");
        // 链上无 O1 → 本地有、外部无

        ReconciliationReport report = service.reconcileWithChain();

        assertEquals(0, report.getMatchedCount());
        assertEquals(1, report.getDiscrepancyCount());
        assertTrue(report.getDiscrepancies().get(0).contains("本地有、外部无"));
    }

    @Test
    void reconcileWithChain_externalOnly_shouldReportMissingLocal() {
        // 本地无，链上有 O9 → 外部有、本地无
        chainSource.feed(List.of(record("O9", "88")));

        ReconciliationReport report = service.reconcileWithChain();

        assertEquals(0, report.getMatchedCount());
        assertEquals(1, report.getDiscrepancyCount());
        assertTrue(report.getDiscrepancies().get(0).contains("外部有、本地无"));
    }

    @Test
    void reconcileWithBank_amountMismatch_shouldReport() {
        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");
        bankSource.feed(List.of(record("O1", "99")));

        ReconciliationReport report = service.reconcileWithBank();

        assertEquals(0, report.getMatchedCount());
        assertEquals(1, report.getDiscrepancyCount());
        assertTrue(report.getDiscrepancies().get(0).contains("金额不符"));
    }

    @Test
    void reportDiscrepancy_shouldReturnSameReport() {
        ReconciliationReport report = service.reconcileWithChain();
        ReconciliationReport reported = service.reportDiscrepancy(report);
        assertEquals(report, reported);
    }

    // === Path C 新增：结构化维度断言 ===

    @Test
    void reconcileWithChain_shouldFillStructuredDimensions() {
        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");
        chainSource.feed(List.of(record("O1", "100")));

        ReconciliationReport report = service.reconcileWithChain();

        assertEquals("CHAIN", report.getSource());
        assertEquals(1, report.getTotalLocal());
        assertEquals(1, report.getTotalExternal());
        assertEquals(0, new BigDecimal("0").compareTo(report.getTotalDiscrepancyAmount()));
        assertNotNull(report.getReconciledAt());
        assertNotNull(report.getDetails());
        assertTrue(report.getDetails().isEmpty());
    }

    @Test
    void reconcileWithBank_shouldFillSourceAsBank() {
        ReconciliationReport report = service.reconcileWithBank();

        assertEquals("BANK", report.getSource());
    }

    @Test
    void reconcile_localOnly_shouldFillDetailWithTypeAndAmount() {
        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");

        ReconciliationReport report = service.reconcileWithChain();

        assertEquals(1, report.getDetails().size());
        DiscrepancyDetail detail = report.getDetails().get(0);
        assertEquals(DiscrepancyDetail.Type.LOCAL_ONLY, detail.getType());
        assertEquals("O1", detail.getReference());
        assertEquals(0, new BigDecimal("100").compareTo(detail.getLocalAmount()));
        assertEquals(null, detail.getExternalAmount());
        assertEquals(0, new BigDecimal("100").compareTo(report.getTotalDiscrepancyAmount()));
    }

    @Test
    void reconcile_externalOnly_shouldFillDetailWithExternalAmount() {
        chainSource.feed(List.of(record("O9", "88")));

        ReconciliationReport report = service.reconcileWithChain();

        assertEquals(1, report.getDetails().size());
        DiscrepancyDetail detail = report.getDetails().get(0);
        assertEquals(DiscrepancyDetail.Type.EXTERNAL_ONLY, detail.getType());
        assertEquals("O9", detail.getReference());
        assertEquals(null, detail.getLocalAmount());
        assertEquals(0, new BigDecimal("88").compareTo(detail.getExternalAmount()));
        assertEquals(0, new BigDecimal("88").compareTo(report.getTotalDiscrepancyAmount()));
    }

    @Test
    void reconcile_amountMismatch_shouldFillDetailAndSumDiff() {
        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");
        bankSource.feed(List.of(record("O1", "99")));

        ReconciliationReport report = service.reconcileWithBank();

        assertEquals(1, report.getDetails().size());
        DiscrepancyDetail detail = report.getDetails().get(0);
        assertEquals(DiscrepancyDetail.Type.AMOUNT_MISMATCH, detail.getType());
        assertEquals("O1", detail.getReference());
        assertEquals(0, new BigDecimal("100").compareTo(detail.getLocalAmount()));
        assertEquals(0, new BigDecimal("99").compareTo(detail.getExternalAmount()));
        // 差错金额 = |100 - 99| = 1
        assertEquals(0, new BigDecimal("1").compareTo(report.getTotalDiscrepancyAmount()));
    }

    @Test
    void reconcile_mixedDiscrepancies_shouldSumAllAmounts() {
        // 本地有外部无：100；金额不符差额：1；外部有本地无：50
        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");
        ledger.bookSettlement("M001", new BigDecimal("200"), "O2");
        chainSource.feed(List.of(
                record("O2", "201"),
                record("O9", "50")));

        ReconciliationReport report = service.reconcileWithChain();

        assertEquals(3, report.getDetails().size());
        assertEquals(3, report.getDiscrepancyCount());
        assertEquals(0, new BigDecimal("151").compareTo(report.getTotalDiscrepancyAmount()));
        assertEquals(2, report.getTotalLocal());
        assertEquals(2, report.getTotalExternal());
    }

    private SettlementRecord record(String ref, String amount) {
        return new SettlementRecord(ref, new BigDecimal(amount), "USDT", Instant.now());
    }
}
