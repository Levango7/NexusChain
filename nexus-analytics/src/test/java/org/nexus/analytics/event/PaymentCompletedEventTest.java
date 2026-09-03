package org.nexus.analytics.event;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link PaymentCompletedEvent} 单元测试。
 *
 * <p>覆盖事件构造与全部 getter。
 */
class PaymentCompletedEventTest {

    @Test
    void getters_shouldReturnAllFields() {
        Instant ts = Instant.parse("2026-01-01T00:00:00Z");
        PaymentCompletedEvent ev = new PaymentCompletedEvent(
                this, 100L, new BigDecimal("99.99"), "USD", "POLYGON",
                200L, "0xtxhash", "0xpayer", "0xpayee", ts, 42L, 5);

        assertEquals(100L, ev.getPaymentId());
        assertEquals(new BigDecimal("99.99"), ev.getAmount());
        assertEquals("USD", ev.getCurrency());
        assertEquals("POLYGON", ev.getConnector());
        assertEquals(200L, ev.getMerchantId());
        assertEquals("0xtxhash", ev.getChainTxHash());
        assertEquals("0xpayer", ev.getPayerAddress());
        assertEquals("0xpayee", ev.getPayeeAddress());
        assertEquals(ts, ev.getOccurredAt());
        assertEquals(this, ev.getSource());
        assertEquals(42L, ev.getLatencyMs());
        assertEquals(5, ev.getCostBps());
    }

    @Test
    void getters_withNulls_shouldReturnNull() {
        PaymentCompletedEvent ev = new PaymentCompletedEvent(
                this, null, null, null, null, null, null, null, null, null, 0L, 0);

        assertEquals(null, ev.getPaymentId());
        assertEquals(null, ev.getAmount());
        assertEquals(null, ev.getCurrency());
        assertEquals(null, ev.getConnector());
        assertEquals(null, ev.getMerchantId());
        assertEquals(null, ev.getChainTxHash());
        assertEquals(null, ev.getPayerAddress());
        assertEquals(null, ev.getPayeeAddress());
        assertEquals(null, ev.getOccurredAt());
        assertEquals(0L, ev.getLatencyMs());
        assertEquals(0, ev.getCostBps());
    }
}
