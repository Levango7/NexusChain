package org.nexus.bridge.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link InsuranceFundLedgerEntry} 单元测试：覆盖构造、字段读写、equals/hashCode/toString。
 */
class InsuranceFundLedgerEntryTest {

    @Test
    @DisplayName("默认构造产生空条目")
    void defaultConstructor_emptyEntry() {
        InsuranceFundLedgerEntry entry = new InsuranceFundLedgerEntry();
        assertNull(entry.getEntryId());
        assertNull(entry.getType());
        assertNull(entry.getAmount());
        assertNull(entry.getBalanceAfter());
        assertNull(entry.getPartyId());
        assertNull(entry.getReason());
        assertNull(entry.getCreatedAt());
    }

    @Test
    @DisplayName("全参数构造应正确设置字段并填充 createdAt")
    void fullConstructor_setsFields() {
        Instant before = Instant.now();
        InsuranceFundLedgerEntry entry = new InsuranceFundLedgerEntry(
                "DEPOSIT", new BigDecimal("1000"), new BigDecimal("1000"), "depositor-1", "initial deposit");
        Instant after = Instant.now();

        assertEquals("DEPOSIT", entry.getType());
        assertEquals(0, new BigDecimal("1000").compareTo(entry.getAmount()));
        assertEquals(0, new BigDecimal("1000").compareTo(entry.getBalanceAfter()));
        assertEquals("depositor-1", entry.getPartyId());
        assertEquals("initial deposit", entry.getReason());
        assertNotNull(entry.getCreatedAt());
        assertTrue(entry.getCreatedAt().isAfter(before.minusSeconds(1)));
        assertTrue(entry.getCreatedAt().isBefore(after.plusSeconds(1)));
    }

    @Test
    @DisplayName("setter/getter 正确往返")
    void settersGetters_roundTrip() {
        InsuranceFundLedgerEntry entry = new InsuranceFundLedgerEntry();
        entry.setEntryId(42L);
        entry.setType("COMPENSATE");
        entry.setAmount(new BigDecimal("500"));
        entry.setBalanceAfter(new BigDecimal("500"));
        entry.setPartyId("victim-1");
        entry.setReason("compensation");
        Instant ts = Instant.now();
        entry.setCreatedAt(ts);

        assertEquals(42L, entry.getEntryId());
        assertEquals("COMPENSATE", entry.getType());
        assertEquals(0, new BigDecimal("500").compareTo(entry.getAmount()));
        assertEquals(0, new BigDecimal("500").compareTo(entry.getBalanceAfter()));
        assertEquals("victim-1", entry.getPartyId());
        assertEquals("compensation", entry.getReason());
        assertEquals(ts, entry.getCreatedAt());
    }

    @Test
    @DisplayName("equals/hashCode 基于 entryId")
    void equalsHashcode_basedOnEntryId() {
        InsuranceFundLedgerEntry e1 = new InsuranceFundLedgerEntry();
        e1.setEntryId(1L);
        InsuranceFundLedgerEntry e2 = new InsuranceFundLedgerEntry();
        e2.setEntryId(1L);
        e2.setType("DIFF");
        InsuranceFundLedgerEntry e3 = new InsuranceFundLedgerEntry();
        e3.setEntryId(2L);

        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
        assertNotEquals(e1, e3);
        assertEquals(e1, e1);
        assertNotEquals(e1, null);
        assertNotEquals(e1, "string");
    }

    @Test
    @DisplayName("equals: null entryId 的两个对象应相等")
    void equals_nullEntryId() {
        InsuranceFundLedgerEntry e1 = new InsuranceFundLedgerEntry();
        InsuranceFundLedgerEntry e2 = new InsuranceFundLedgerEntry();
        assertEquals(e1, e2);
    }

    @Test
    @DisplayName("toString 应包含关键字段")
    void toString_containsKeyFields() {
        InsuranceFundLedgerEntry entry = new InsuranceFundLedgerEntry(
                "DEPOSIT", new BigDecimal("1000"), new BigDecimal("1000"), "p1", "r1");
        entry.setEntryId(99L);
        String str = entry.toString();
        assertTrue(str.contains("DEPOSIT"));
        assertTrue(str.contains("1000"));
        assertTrue(str.contains("p1"));
        assertTrue(str.contains("99"));
        assertTrue(str.startsWith("InsuranceFundLedgerEntry{"));
    }
}