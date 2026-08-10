package org.nexus.governance.delegation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LockRecord} 单元测试。
 */
class LockRecordTest {

    @Test
    void validConstruction() {
        Instant start = Instant.now();
        Instant end = start.plus(Duration.ofDays(30));
        LockRecord r = new LockRecord("voter", new BigDecimal("100"), start, end);
        assertEquals("voter", r.getVoter());
        assertEquals(new BigDecimal("100"), r.getAmount());
        assertEquals(start, r.getLockStart());
        assertEquals(end, r.getLockEnd());
    }

    @Test
    void durationIsEndMinusStart() {
        Instant start = Instant.now();
        Instant end = start.plus(Duration.ofDays(10));
        LockRecord r = new LockRecord("v", BigDecimal.ONE, start, end);
        assertEquals(Duration.ofDays(10), r.getDuration());
    }

    @Test
    void isMaturedTrueAfterLockEnd() {
        Instant start = Instant.now();
        Instant end = start.plus(Duration.ofDays(1));
        LockRecord r = new LockRecord("v", BigDecimal.ONE, start, end);
        assertTrue(r.isMatured(end));
        assertTrue(r.isMatured(end.plusSeconds(1)));
    }

    @Test
    void isMaturedFalseBeforeLockEnd() {
        Instant start = Instant.now();
        Instant end = start.plus(Duration.ofDays(1));
        LockRecord r = new LockRecord("v", BigDecimal.ONE, start, end);
        assertFalse(r.isMatured(start));
    }

    @Test
    void isMaturedNullNowReturnsFalse() {
        Instant start = Instant.now();
        LockRecord r = new LockRecord("v", BigDecimal.ONE, start, start.plusSeconds(1));
        assertFalse(r.isMatured(null));
    }

    @Test
    void nullVoterThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new LockRecord(null, BigDecimal.ONE, Instant.now(), Instant.now().plusSeconds(1)));
    }

    @Test
    void nullAmountThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new LockRecord("v", null, Instant.now(), Instant.now().plusSeconds(1)));
    }

    @Test
    void zeroAmountThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new LockRecord("v", BigDecimal.ZERO, Instant.now(), Instant.now().plusSeconds(1)));
    }

    @Test
    void negativeAmountThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new LockRecord("v", new BigDecimal("-1"), Instant.now(), Instant.now().plusSeconds(1)));
    }

    @Test
    void nullLockStartThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new LockRecord("v", BigDecimal.ONE, null, Instant.now()));
    }

    @Test
    void nullLockEndThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                new LockRecord("v", BigDecimal.ONE, Instant.now(), null));
    }

    @Test
    void lockEndNotAfterStartThrows() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
                new LockRecord("v", BigDecimal.ONE, now, now));
        assertThrows(IllegalArgumentException.class, () ->
                new LockRecord("v", BigDecimal.ONE, now, now.minusSeconds(1)));
    }
}