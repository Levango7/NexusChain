package org.nexus.settlement.clearing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link LedgerEntry} 单元测试。
 * <p>覆盖默认构造、全参构造、getter/setter 全字段。</p>
 */
class LedgerEntryTest {

    @Test
    void defaultConstructor_shouldHaveNulls() {
        LedgerEntry e = new LedgerEntry();
        assertNull(e.getEntryId());
        assertNull(e.getAccount());
        assertNull(e.getDirection());
        assertNull(e.getAmount());
        assertNull(e.getReference());
        assertNull(e.getBookedAt());
    }

    @Test
    void fullConstructor_shouldSetAllFields() {
        Instant now = Instant.now();
        LedgerEntry e = new LedgerEntry(
                "E1", "ACC", LedgerEntry.Direction.CREDIT,
                new BigDecimal("100"), "REF", now);

        assertEquals("E1", e.getEntryId());
        assertEquals("ACC", e.getAccount());
        assertEquals(LedgerEntry.Direction.CREDIT, e.getDirection());
        assertEquals(new BigDecimal("100"), e.getAmount());
        assertEquals("REF", e.getReference());
        assertEquals(now, e.getBookedAt());
    }

    @Test
    void setters_shouldRoundTrip() {
        LedgerEntry e = new LedgerEntry();
        Instant now = Instant.now();
        e.setEntryId("E2");
        e.setAccount("ACC2");
        e.setDirection(LedgerEntry.Direction.DEBIT);
        e.setAmount(BigDecimal.TEN);
        e.setReference("R2");
        e.setBookedAt(now);

        assertEquals("E2", e.getEntryId());
        assertEquals("ACC2", e.getAccount());
        assertEquals(LedgerEntry.Direction.DEBIT, e.getDirection());
        assertEquals(BigDecimal.TEN, e.getAmount());
        assertEquals("R2", e.getReference());
        assertEquals(now, e.getBookedAt());
    }

    @Test
    void directionEnum_shouldContainAllVariants() {
        assertEquals(2, LedgerEntry.Direction.values().length);
        assertEquals(LedgerEntry.Direction.DEBIT, LedgerEntry.Direction.valueOf("DEBIT"));
        assertEquals(LedgerEntry.Direction.CREDIT, LedgerEntry.Direction.valueOf("CREDIT"));
    }
}