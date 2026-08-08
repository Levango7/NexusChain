package org.nexus.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TimeUtils} 单元测试。
 */
class TimeUtilsTest {

    @Test
    void minutesToMillis() {
        assertEquals(60_000L, TimeUtils.minutesToMillis(1));
        assertEquals(0L, TimeUtils.minutesToMillis(0));
        assertEquals(120_000L, TimeUtils.minutesToMillis(2));
    }

    @Test
    void secondsToMillis() {
        assertEquals(1000L, TimeUtils.secondsToMillis(1));
        assertEquals(0L, TimeUtils.secondsToMillis(0));
        assertEquals(30_000L, TimeUtils.secondsToMillis(30));
    }

    @Test
    void millisToMinutes() {
        assertEquals(1L, TimeUtils.millisToMinutes(60_000L));
        assertEquals(0L, TimeUtils.millisToMinutes(0));
        assertEquals(2L, TimeUtils.millisToMinutes(120_000L));
    }

    @Test
    void millisToSeconds() {
        assertEquals(1L, TimeUtils.millisToSeconds(1000L));
        assertEquals(0L, TimeUtils.millisToSeconds(0));
        assertEquals(30L, TimeUtils.millisToSeconds(30_000L));
    }

    @Test
    void timeAfterMillisIsFuture() {
        long now = System.currentTimeMillis();
        long future = TimeUtils.timeAfterMillis(5000L);
        assertTrue(future >= now + 5000L - 100); // 允许 100ms 误差
    }

    @Test
    void long2BytesAndBytes2LongRoundTrip() {
        long val = 123456789L;
        byte[] bytes = TimeUtils.long2Bytes(val);
        assertEquals(8, bytes.length);
        assertEquals(val, TimeUtils.bytes2Long(bytes));
    }

    @Test
    void long2BytesZero() {
        byte[] bytes = TimeUtils.long2Bytes(0L);
        assertEquals(8, bytes.length);
        for (byte b : bytes) {
            assertEquals(0, b);
        }
    }

    @Test
    void long2BytesMaxValue() {
        byte[] bytes = TimeUtils.long2Bytes(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, TimeUtils.bytes2Long(bytes));
    }

    @Test
    void long2BytesNegative() {
        byte[] bytes = TimeUtils.long2Bytes(-1L);
        assertEquals(-1L, TimeUtils.bytes2Long(bytes));
    }
}