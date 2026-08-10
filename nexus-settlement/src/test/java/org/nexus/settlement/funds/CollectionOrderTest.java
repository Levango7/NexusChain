package org.nexus.settlement.funds;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link CollectionOrder} 实体单元测试。
 */
class CollectionOrderTest {

    @Test
    void defaultsAndSetters_shouldRoundTrip() {
        CollectionOrder o = new CollectionOrder();
        assertNull(o.getOrderId());
        assertNull(o.getSourceAddress());
        assertNull(o.getTargetAddress());
        assertNull(o.getAmount());
        assertNull(o.getCurrency());
        assertNull(o.getStatus());
        assertNull(o.getCreatedAt());

        Instant now = Instant.now();
        o.setOrderId("SW-1");
        o.setSourceAddress("0xFROM");
        o.setTargetAddress("0xTO");
        o.setAmount(new BigDecimal("100"));
        o.setCurrency("USDT");
        o.setStatus(CollectionOrder.OrderStatus.SETTLED);
        o.setCreatedAt(now);

        assertEquals("SW-1", o.getOrderId());
        assertEquals("0xFROM", o.getSourceAddress());
        assertEquals("0xTO", o.getTargetAddress());
        assertEquals(new BigDecimal("100"), o.getAmount());
        assertEquals("USDT", o.getCurrency());
        assertEquals(CollectionOrder.OrderStatus.SETTLED, o.getStatus());
        assertEquals(now, o.getCreatedAt());
    }

    @Test
    void orderStatusEnum_shouldContainAllVariants() {
        assertEquals(4, CollectionOrder.OrderStatus.values().length);
        assertEquals(CollectionOrder.OrderStatus.PENDING, CollectionOrder.OrderStatus.valueOf("PENDING"));
        assertEquals(CollectionOrder.OrderStatus.SWEEPING, CollectionOrder.OrderStatus.valueOf("SWEEPING"));
        assertEquals(CollectionOrder.OrderStatus.SETTLED, CollectionOrder.OrderStatus.valueOf("SETTLED"));
        assertEquals(CollectionOrder.OrderStatus.FAILED, CollectionOrder.OrderStatus.valueOf("FAILED"));
    }
}