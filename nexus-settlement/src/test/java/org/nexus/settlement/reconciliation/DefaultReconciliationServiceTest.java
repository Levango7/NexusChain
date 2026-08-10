package org.nexus.settlement.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.clearing.Ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultReconciliationService} 单元测试。
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

    private SettlementRecord record(String ref, String amount) {
        return new SettlementRecord(ref, new BigDecimal(amount), "USDT", Instant.now());
    }
}
