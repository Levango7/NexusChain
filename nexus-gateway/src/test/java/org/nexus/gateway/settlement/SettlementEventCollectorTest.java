package org.nexus.gateway.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.analytics.event.PaymentCompletedEvent;
import org.nexus.settlement.clearing.ClearingOrder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SettlementEventCollector} 单元测试。
 *
 * <p>覆盖：事件 → ClearingOrder 字段映射（含 Path A 新增的链上关联字段）、
 * staging/drain 语义、null 事件安全、多事件累积。</p>
 */
class SettlementEventCollectorTest {

    private SettlementEventCollector collector;

    @BeforeEach
    void setUp() {
        collector = new SettlementEventCollector();
    }

    private PaymentCompletedEvent event(Long paymentId, BigDecimal amount, Long merchantId,
                                        String chainTxHash, String connector,
                                        long latencyMs, int costBps,
                                        String payer, String payee) {
        return new PaymentCompletedEvent(
                this, paymentId, amount, "USD", connector,
                merchantId, chainTxHash, payer, payee, Instant.now(),
                latencyMs, costBps);
    }

    @Test
    void onPaymentCompleted_normalEvent_shouldAddPendingOrderToStaging() {
        collector.onPaymentCompleted(event(1L, new BigDecimal("100.50"), 200L,
                "0xabc", "POLYGON", 42L, 5, "0xpayer", "0xpayee"));

        assertEquals(1, collector.stagingSize());

        ClearingOrder order = collector.drainStaging().get(0);
        assertEquals(0, collector.stagingSize());
        assertNotNull(order.getOrderId());
        assertTrue(order.getOrderId().startsWith("clr_"));
        assertEquals(1L, order.getPaymentId());
        assertEquals("200", order.getMerchantId());
        assertEquals(new BigDecimal("100.50"), order.getAmount());
        assertEquals("USD", order.getCurrency());
        assertEquals("0xabc", order.getChainTxHash());
        assertEquals("POLYGON", order.getConnectorId());
        assertEquals(42L, order.getRoutingLatencyMs());
        assertEquals(5, order.getCostBps());
        assertEquals("0xpayer", order.getPayerAddress());
        assertEquals("0xpayee", order.getPayeeAddress());
        assertEquals(ClearingOrder.OrderStatus.PENDING, order.getStatus());
        assertEquals("T0", order.getSettlementCycle());
        assertNotNull(order.getCreatedAt());
    }

    @Test
    void onPaymentCompleted_multipleEvents_shouldAccumulateInStaging() {
        collector.onPaymentCompleted(event(1L, new BigDecimal("10"), 100L, "h1", "ETH", 10L, 1, "A", "B"));
        collector.onPaymentCompleted(event(2L, new BigDecimal("20"), 200L, "h2", "BSC", 20L, 2, "C", "D"));

        assertEquals(2, collector.stagingSize());
    }

    @Test
    void onPaymentCompleted_nullEvent_shouldBeNoOp() {
        collector.onPaymentCompleted(null);

        assertEquals(0, collector.stagingSize());
    }

    @Test
    void drainStaging_shouldReturnAllAndClearStaging() {
        collector.onPaymentCompleted(event(1L, new BigDecimal("10"), 100L, "h1", "ETH", 10L, 1, "A", "B"));
        collector.onPaymentCompleted(event(2L, new BigDecimal("20"), 200L, "h2", "BSC", 20L, 2, "C", "D"));

        List<ClearingOrder> drained = collector.drainStaging();
        assertEquals(2, drained.size());
        assertEquals(0, collector.stagingSize());

        assertTrue(collector.drainStaging().isEmpty());
    }

    @Test
    void mapToClearingOrder_nullMerchantId_shouldMapToNull() {
        PaymentCompletedEvent ev = event(1L, new BigDecimal("10"), null, "h1", "ETH", 5L, 1, "A", "B");

        ClearingOrder order = collector.mapToClearingOrder(ev);

        assertNull(order.getMerchantId());
        // merchantId 为 null 的订单由 DefaultClearingEngine.settle 标 FAILED，不会入账
        assertEquals(ClearingOrder.OrderStatus.PENDING, order.getStatus());
    }

    @Test
    void mapToClearingOrder_nullOccurredAt_shouldFallbackToNow() {
        PaymentCompletedEvent ev = new PaymentCompletedEvent(
                this, 1L, new BigDecimal("10"), "USD", "ETH",
                100L, "h1", "A", "B", null, 8L, 3);

        ClearingOrder order = collector.mapToClearingOrder(ev);

        assertNotNull(order.getCreatedAt());
    }

    @Test
    void stagingSize_shouldReflectCurrentCount() {
        assertEquals(0, collector.stagingSize());

        collector.onPaymentCompleted(event(1L, new BigDecimal("10"), 100L, "h1", "ETH", 10L, 1, "A", "B"));
        assertEquals(1, collector.stagingSize());

        collector.drainStaging();
        assertEquals(0, collector.stagingSize());
    }
}
