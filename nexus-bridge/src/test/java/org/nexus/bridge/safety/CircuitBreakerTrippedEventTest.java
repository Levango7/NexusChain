package org.nexus.bridge.safety;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CircuitBreakerTrippedEvent} 单元测试：覆盖构造、字段读取与 toString。
 */
class CircuitBreakerTrippedEventTest {

    @Test
    @DisplayName("构造应正确设置 source、reason 与 occurredAt")
    void constructor_setsAllFields() {
        Object source = new Object();
        Instant before = Instant.now();
        CircuitBreakerTrippedEvent event = new CircuitBreakerTrippedEvent(
                source, "failure rate exceeded", Instant.now());
        Instant after = Instant.now();

        assertSame(source, event.getSource());
        assertEquals("failure rate exceeded", event.getReason());
        assertNotNull(event.getOccurredAt());
        assertTrue(event.getOccurredAt().isAfter(before.minusSeconds(1)));
        assertTrue(event.getOccurredAt().isBefore(after.plusSeconds(1)));
    }

    @Test
    @DisplayName("null reason 应被允许")
    void constructor_nullReason() {
        CircuitBreakerTrippedEvent event = new CircuitBreakerTrippedEvent(
                this, null, Instant.now());
        assertNull(event.getReason());
    }

    @Test
    @DisplayName("toString 应包含 reason 与 occurredAt")
    void toString_containsFields() {
        Instant ts = Instant.now();
        CircuitBreakerTrippedEvent event = new CircuitBreakerTrippedEvent(
                this, "test reason", ts);
        String str = event.toString();
        assertTrue(str.contains("test reason"));
        assertTrue(str.contains(ts.toString()));
        assertTrue(str.startsWith("CircuitBreakerTrippedEvent{"));
    }
}