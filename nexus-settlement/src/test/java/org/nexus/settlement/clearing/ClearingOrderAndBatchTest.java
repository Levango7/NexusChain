package org.nexus.settlement.clearing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link ClearingOrder}、{@link SettlementBatch} 实体单元测试。
 * <p>覆盖默认构造与所有 getter/setter，提升实体类覆盖率。</p>
 */
class ClearingOrderAndBatchTest {

    @Test
    void clearingOrder_defaultsAndSetters() {
        ClearingOrder o = new ClearingOrder();
        assertNull(o.getOrderId());
        assertNull(o.getMerchantId());
        assertNull(o.getAmount());
        assertNull(o.getCurrency());
        assertNull(o.getSettlementCycle());
        assertNull(o.getStatus());
        assertNull(o.getCreatedAt());

        Instant now = Instant.now();
        o.setOrderId("O1");
        o.setMerchantId("M1");
        o.setAmount(new BigDecimal("100"));
        o.setCurrency("USDT");
        o.setSettlementCycle("T+1");
        o.setStatus(ClearingOrder.OrderStatus.SETTLED);
        o.setCreatedAt(now);

        assertEquals("O1", o.getOrderId());
        assertEquals("M1", o.getMerchantId());
        assertEquals(new BigDecimal("100"), o.getAmount());
        assertEquals("USDT", o.getCurrency());
        assertEquals("T+1", o.getSettlementCycle());
        assertEquals(ClearingOrder.OrderStatus.SETTLED, o.getStatus());
        assertEquals(now, o.getCreatedAt());
    }

    @Test
    void clearingOrder_orderStatusEnum_shouldContainAllVariants() {
        assertEquals(3, ClearingOrder.OrderStatus.values().length);
        assertEquals(ClearingOrder.OrderStatus.PENDING, ClearingOrder.OrderStatus.valueOf("PENDING"));
        assertEquals(ClearingOrder.OrderStatus.SETTLED, ClearingOrder.OrderStatus.valueOf("SETTLED"));
        assertEquals(ClearingOrder.OrderStatus.FAILED, ClearingOrder.OrderStatus.valueOf("FAILED"));
    }

    @Test
    void settlementBatch_defaultsAndSetters() {
        SettlementBatch b = new SettlementBatch();
        assertNull(b.getBatchNo());
        assertNull(b.getOrders());
        assertNull(b.getSettlementAmount());
        assertNull(b.getCurrency());
        assertNull(b.getStatus());
        assertNull(b.getCreatedAt());

        Instant now = Instant.now();
        ClearingOrder o = new ClearingOrder();
        o.setOrderId("O1");
        b.setBatchNo("B1");
        b.setOrders(List.of(o));
        b.setSettlementAmount(new BigDecimal("100"));
        b.setCurrency("USDT");
        b.setStatus(SettlementBatch.BatchStatus.SETTLED);
        b.setCreatedAt(now);

        assertEquals("B1", b.getBatchNo());
        assertEquals(1, b.getOrders().size());
        assertEquals(new BigDecimal("100"), b.getSettlementAmount());
        assertEquals("USDT", b.getCurrency());
        assertEquals(SettlementBatch.BatchStatus.SETTLED, b.getStatus());
        assertEquals(now, b.getCreatedAt());
    }

    @Test
    void settlementBatch_batchStatusEnum_shouldContainAllVariants() {
        assertEquals(3, SettlementBatch.BatchStatus.values().length);
        assertEquals(SettlementBatch.BatchStatus.PENDING, SettlementBatch.BatchStatus.valueOf("PENDING"));
        assertEquals(SettlementBatch.BatchStatus.SETTLED, SettlementBatch.BatchStatus.valueOf("SETTLED"));
        assertEquals(SettlementBatch.BatchStatus.FAILED, SettlementBatch.BatchStatus.valueOf("FAILED"));
    }
}