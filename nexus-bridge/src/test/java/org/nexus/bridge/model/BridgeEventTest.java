package org.nexus.bridge.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link BridgeEvent} 单元测试：覆盖字段读写、equals/hashCode/toString 与事件类型枚举。
 */
class BridgeEventTest {

    @Test
    @DisplayName("默认构造产生空事件")
    void defaultConstructor_emptyEvent() {
        BridgeEvent event = new BridgeEvent();
        assertNull(event.getEventId());
        assertNull(event.getTxId());
        assertNull(event.getEventType());
        assertEquals(0, event.getAmount());
        assertNull(event.getTimestamp());
    }

    @Test
    @DisplayName("所有 setter/getter 正确往返")
    void settersGetters_roundTrip() {
        BridgeEvent event = new BridgeEvent();
        event.setEventId("evt-001");
        event.setTxId("tx-001");
        event.setEventType(BridgeEvent.EventType.LOCK_CONFIRMED);
        event.setSourceChainId("ethereum");
        event.setTargetChainId("bsc");
        event.setAmount(1000L);
        event.setActor("0xActor");
        event.setDescription("Lock confirmed");
        Instant ts = Instant.now();
        event.setTimestamp(ts);
        event.setData("{\"key\":\"value\"}");

        assertEquals("evt-001", event.getEventId());
        assertEquals("tx-001", event.getTxId());
        assertEquals(BridgeEvent.EventType.LOCK_CONFIRMED, event.getEventType());
        assertEquals("ethereum", event.getSourceChainId());
        assertEquals("bsc", event.getTargetChainId());
        assertEquals(1000L, event.getAmount());
        assertEquals("0xActor", event.getActor());
        assertEquals("Lock confirmed", event.getDescription());
        assertEquals(ts, event.getTimestamp());
        assertEquals("{\"key\":\"value\"}", event.getData());
    }

    @Test
    @DisplayName("EventType 枚举应包含所有事件类型")
    void eventType_enumValues() {
        BridgeEvent.EventType[] types = BridgeEvent.EventType.values();
        // LOCK_INITIATED, LOCK_CONFIRMED, MINT_INITIATED, MINT_CONFIRMED,
        // BURN_INITIATED, BURN_CONFIRMED, UNLOCK_INITIATED, UNLOCK_CONFIRMED,
        // BRIDGE_PAUSED, BRIDGE_RESUMED, BRIDGE_EMERGENCY_STOP,
        // VALIDATOR_ADDED, VALIDATOR_REMOVED, THRESHOLD_CHANGED,
        // TRANSACTION_FAILED, TRANSACTION_TIMEOUT, DAILY_LIMIT_EXCEEDED
        assertEquals(17, types.length);
        assertTrue(Arrays.asList(types).contains(BridgeEvent.EventType.LOCK_INITIATED));
        assertTrue(Arrays.asList(types).contains(BridgeEvent.EventType.BRIDGE_EMERGENCY_STOP));
        assertTrue(Arrays.asList(types).contains(BridgeEvent.EventType.DAILY_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("equals/hashCode 基于 eventId")
    void equalsHashcode_basedOnEventId() {
        BridgeEvent e1 = new BridgeEvent();
        e1.setEventId("evt-1");
        BridgeEvent e2 = new BridgeEvent();
        e2.setEventId("evt-1");
        e2.setAmount(999L);
        BridgeEvent e3 = new BridgeEvent();
        e3.setEventId("evt-2");

        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
        assertNotEquals(e1, e3);
        assertEquals(e1, e1);
        assertNotEquals(e1, null);
        assertNotEquals(e1, "string");
    }

    @Test
    @DisplayName("equals: null eventId 的两个对象应相等")
    void equals_nullEventId() {
        BridgeEvent e1 = new BridgeEvent();
        BridgeEvent e2 = new BridgeEvent();
        assertEquals(e1, e2);
    }

    @Test
    @DisplayName("toString 应包含关键字段")
    void toString_containsKeyFields() {
        BridgeEvent event = new BridgeEvent();
        event.setEventId("evt-001");
        event.setEventType(BridgeEvent.EventType.MINT_CONFIRMED);
        event.setTxId("tx-001");
        event.setSourceChainId("ethereum");
        event.setTargetChainId("bsc");
        event.setAmount(1000L);
        event.setActor("0xActor");
        String str = event.toString();
        assertTrue(str.contains("evt-001"));
        assertTrue(str.contains("MINT_CONFIRMED"));
        assertTrue(str.contains("tx-001"));
        assertTrue(str.contains("0xActor"));
        assertTrue(str.startsWith("BridgeEvent{"));
    }
}