package org.nexus.bridge.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BridgePauseRecord} 单元测试：覆盖构造、字段读写、equals/hashCode/toString。
 */
class BridgePauseRecordTest {

    @Test
    @DisplayName("默认构造产生空记录")
    void defaultConstructor_emptyRecord() {
        BridgePauseRecord record = new BridgePauseRecord();
        assertNull(record.getBridgeId());
        assertNull(record.getState());
        assertNull(record.getReason());
        assertNull(record.getTriggeredBy());
        assertNull(record.getUpdatedAt());
    }

    @Test
    @DisplayName("全参数构造应正确设置字段并填充 updatedAt")
    void fullConstructor_setsFields() {
        Instant before = Instant.now();
        BridgePauseRecord record = new BridgePauseRecord("bridge-1", "PAUSED", "manual", "validator-1");
        Instant after = Instant.now();

        assertEquals("bridge-1", record.getBridgeId());
        assertEquals("PAUSED", record.getState());
        assertEquals("manual", record.getReason());
        assertEquals("validator-1", record.getTriggeredBy());
        assertNotNull(record.getUpdatedAt());
        assertTrue(record.getUpdatedAt().isAfter(before.minusSeconds(1)));
        assertTrue(record.getUpdatedAt().isBefore(after.plusSeconds(1)));
    }

    @Test
    @DisplayName("setter/getter 正确往返")
    void settersGetters_roundTrip() {
        BridgePauseRecord record = new BridgePauseRecord();
        record.setBridgeId("bridge-2");
        record.setState("EMERGENCY_STOP");
        record.setReason("key leak");
        record.setTriggeredBy("validator-2");
        Instant ts = Instant.now();
        record.setUpdatedAt(ts);

        assertEquals("bridge-2", record.getBridgeId());
        assertEquals("EMERGENCY_STOP", record.getState());
        assertEquals("key leak", record.getReason());
        assertEquals("validator-2", record.getTriggeredBy());
        assertEquals(ts, record.getUpdatedAt());
    }

    @Test
    @DisplayName("equals/hashCode 基于 bridgeId")
    void equalsHashcode_basedOnBridgeId() {
        BridgePauseRecord r1 = new BridgePauseRecord("b1", "PAUSED", null, null);
        BridgePauseRecord r2 = new BridgePauseRecord("b1", "ACTIVE", "diff", "diff");
        BridgePauseRecord r3 = new BridgePauseRecord("b2", "PAUSED", null, null);

        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotEquals(r1, r3);
        assertEquals(r1, r1);
        assertNotEquals(r1, null);
        assertNotEquals(r1, "string");
    }

    @Test
    @DisplayName("toString 应包含关键字段")
    void toString_containsKeyFields() {
        BridgePauseRecord record = new BridgePauseRecord("bridge-1", "PAUSED", "manual", "v1");
        String str = record.toString();
        assertTrue(str.contains("bridge-1"));
        assertTrue(str.contains("PAUSED"));
        assertTrue(str.contains("manual"));
        assertTrue(str.startsWith("BridgePauseRecord{"));
    }
}