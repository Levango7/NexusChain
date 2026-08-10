package org.nexus.settlement.reconciliation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SettlementRecord}、{@link ReconciliationReport} 实体单元测试。
 */
class SettlementRecordAndReportTest {

    @Test
    void settlementRecord_defaultsAndSetters() {
        SettlementRecord r = new SettlementRecord();
        assertNull(r.getReference());
        assertNull(r.getAmount());
        assertNull(r.getCurrency());
        assertNull(r.getRecordedAt());

        Instant now = Instant.now();
        r.setReference("REF-1");
        r.setAmount(new BigDecimal("100"));
        r.setCurrency("USDT");
        r.setRecordedAt(now);

        assertEquals("REF-1", r.getReference());
        assertEquals(new BigDecimal("100"), r.getAmount());
        assertEquals("USDT", r.getCurrency());
        assertEquals(now, r.getRecordedAt());
    }

    @Test
    void settlementRecord_fullConstructor() {
        Instant now = Instant.now();
        SettlementRecord r = new SettlementRecord("R", new BigDecimal("50"), "NEX", now);

        assertEquals("R", r.getReference());
        assertEquals(new BigDecimal("50"), r.getAmount());
        assertEquals("NEX", r.getCurrency());
        assertEquals(now, r.getRecordedAt());
    }

    @Test
    void reconciliationReport_defaultsAndSetters() {
        ReconciliationReport r = new ReconciliationReport();
        assertNull(r.getReconcileDate());
        assertEquals(0L, r.getMatchedCount());
        assertEquals(0L, r.getDiscrepancyCount());
        assertNull(r.getDiscrepancies());

        LocalDate today = LocalDate.now();
        r.setReconcileDate(today);
        r.setMatchedCount(5);
        r.setDiscrepancyCount(2);
        r.setDiscrepancies(List.of("err1", "err2"));

        assertEquals(today, r.getReconcileDate());
        assertEquals(5L, r.getMatchedCount());
        assertEquals(2L, r.getDiscrepancyCount());
        assertEquals(2, r.getDiscrepancies().size());
    }
}