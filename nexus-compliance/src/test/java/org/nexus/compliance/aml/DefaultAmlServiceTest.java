package org.nexus.compliance.aml;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultAmlService} 单元测试。
 */
class DefaultAmlServiceTest {

    private InMemorySanctionListChecker checker;
    private DefaultAmlService service;

    @BeforeEach
    void setUp() {
        checker = new InMemorySanctionListChecker();
        service = new DefaultAmlService(checker);
    }

    /** 用于反射提取的最小交易 DTO */
    static class TestTransaction {
        private final String fromAddress;
        private final String toAddress;
        private final BigDecimal amount;

        TestTransaction(String from, String to, BigDecimal amount) {
            this.fromAddress = from;
            this.toAddress = to;
            this.amount = amount;
        }

        public String getFromAddress() { return fromAddress; }
        public String getToAddress() { return toAddress; }
        public BigDecimal getAmount() { return amount; }
    }

    @Test
    void screen_cleanTransaction_shouldBeLow() {
        TestTransaction tx = new TestTransaction("addrA", "addrB", new BigDecimal("100"));

        ScreeningResult result = service.screen(tx);

        assertEquals("LOW", result.getRiskLevel());
        assertTrue(result.getHitLists().isEmpty());
        assertFalse(result.isNeedManualReview());
    }

    @Test
    void screen_sanctionedFromAddress_shouldBeHigh() {
        checker.addEntry("evil-address");
        TestTransaction tx = new TestTransaction("evil-address", "addrB", new BigDecimal("100"));

        ScreeningResult result = service.screen(tx);

        assertEquals("HIGH", result.getRiskLevel());
        assertFalse(result.getHitLists().isEmpty());
        assertTrue(result.isNeedManualReview());
    }

    @Test
    void screen_largeAmount_shouldBeMedium() {
        TestTransaction tx = new TestTransaction("addrA", "addrB", new BigDecimal("500000"));

        ScreeningResult result = service.screen(tx);

        assertEquals("MEDIUM", result.getRiskLevel());
    }

    @Test
    void screen_bothAddressesSanctioned_shouldBeCritical() {
        checker.addEntry("evil1");
        checker.addEntry("evil2");
        TestTransaction tx = new TestTransaction("evil1", "evil2", new BigDecimal("100"));

        ScreeningResult result = service.screen(tx);

        assertEquals("CRITICAL", result.getRiskLevel());
        assertTrue(result.isNeedManualReview());
        assertFalse(result.getHitLists().isEmpty());
    }

    @Test
    void screenAddress_hit_shouldBeHigh() {
        checker.addEntry("bad-addr");

        ScreeningResult result = service.screenAddress("bad-addr");

        assertEquals("HIGH", result.getRiskLevel());
        assertTrue(result.isNeedManualReview());
    }

    @Test
    void screenAddress_clean_shouldBeLow() {
        ScreeningResult result = service.screenAddress("clean-addr");
        assertEquals("LOW", result.getRiskLevel());
        assertFalse(result.isNeedManualReview());
    }

    @Test
    void fileSuspiciousReport_shouldAssignIdAndStatus() {
        SuspiciousTransactionReport report = new SuspiciousTransactionReport();
        report.setTransactionDetail("tx=123");
        report.setSuspiciousReason("structuring");

        SuspiciousTransactionReport filed = service.fileSuspiciousReport(report);

        assertEquals(SuspiciousTransactionReport.ReportStatus.SUBMITTED, filed.getReportStatus());
        assertTrue(filed.getReportId().startsWith("STR-"));
        assertEquals(1, service.filedReportCount());
    }
}
