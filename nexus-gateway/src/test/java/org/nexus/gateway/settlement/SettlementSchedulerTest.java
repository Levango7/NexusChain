package org.nexus.gateway.settlement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.analytics.event.PaymentCompletedEvent;
import org.nexus.settlement.clearing.ClearingEngine;
import org.nexus.settlement.clearing.ClearingOrder;
import org.nexus.settlement.clearing.SettlementBatch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SettlementScheduler} 单元测试。
 *
 * <p>覆盖：正常批量结算、staging 为空跳过（不调 batchClear）、
 * 结算开关关闭跳过、batchClear 异常不外抛（调度线程安全）、
 * 混合币种标记 MIXED、批次字段填充。</p>
 */
@ExtendWith(MockitoExtension.class)
class SettlementSchedulerTest {

    @Mock
    private SettlementEventCollector eventCollector;

    @Mock
    private ClearingEngine clearingEngine;

    private SettlementScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SettlementScheduler(eventCollector, clearingEngine, true);
    }

    private ClearingOrder pendingOrder(String currency, String merchantId) {
        ClearingOrder order = new ClearingOrder();
        order.setOrderId("clr_test_1");
        order.setMerchantId(merchantId);
        order.setAmount(new BigDecimal("100"));
        order.setCurrency(currency);
        order.setStatus(ClearingOrder.OrderStatus.PENDING);
        return order;
    }

    @Test
    void settle_withPendingOrders_shouldBatchClear() {
        List<ClearingOrder> orders = List.of(pendingOrder("USD", "100"), pendingOrder("USD", "200"));
        when(eventCollector.drainStaging()).thenReturn(orders);
        when(clearingEngine.batchClear(any(SettlementBatch.class))).thenAnswer(inv -> {
            SettlementBatch b = inv.getArgument(0);
            b.setStatus(SettlementBatch.BatchStatus.SETTLED);
            b.setSettlementAmount(new BigDecimal("200"));
            return b;
        });

        scheduler.settle();

        // 注意：mock 的 thenAnswer 会把 batch.status 突变为 SETTLED，
        // 因此 argThat 不能断言 PENDING 状态，只校验不会被 mock 改变的字段
        verify(clearingEngine).batchClear(argThat(batch ->
                batch.getOrders().size() == 2
                        && "USD".equals(batch.getCurrency())
                        && batch.getBatchNo() != null
                        && batch.getCreatedAt() != null));
    }

    @Test
    void settle_emptyStaging_shouldSkipBatchClear() {
        when(eventCollector.drainStaging()).thenReturn(List.of());

        scheduler.settle();

        verify(clearingEngine, never()).batchClear(any());
    }

    @Test
    void settle_disabled_shouldSkipEverything() {
        SettlementScheduler disabled = new SettlementScheduler(eventCollector, clearingEngine, false);

        disabled.settle();

        verifyNoInteractions(eventCollector, clearingEngine);
    }

    @Test
    void settle_batchClearThrows_shouldNotPropagate() {
        when(eventCollector.drainStaging()).thenReturn(List.of(pendingOrder("USD", "100")));
        when(clearingEngine.batchClear(any(SettlementBatch.class)))
                .thenThrow(new RuntimeException("ledger down"));

        assertDoesNotThrow(() -> scheduler.settle());
    }

    @Test
    void settle_mixedCurrencies_shouldMarkMIXED() {
        when(eventCollector.drainStaging()).thenReturn(
                List.of(pendingOrder("USD", "100"), pendingOrder("CNY", "200")));
        when(clearingEngine.batchClear(any(SettlementBatch.class))).thenAnswer(inv -> {
            SettlementBatch b = inv.getArgument(0);
            b.setStatus(SettlementBatch.BatchStatus.SETTLED);
            return b;
        });

        scheduler.settle();

        verify(clearingEngine).batchClear(argThat(batch -> "MIXED".equals(batch.getCurrency())));
    }

    @Test
    void settle_universalCurrency_shouldUseIt() {
        ClearingOrder o = pendingOrder("CNY", "100");
        when(eventCollector.drainStaging()).thenReturn(List.of(o));
        when(clearingEngine.batchClear(any(SettlementBatch.class))).thenAnswer(inv -> {
            SettlementBatch b = inv.getArgument(0);
            b.setStatus(SettlementBatch.BatchStatus.SETTLED);
            return b;
        });

        scheduler.settle();

        verify(clearingEngine).batchClear(argThat(batch -> "CNY".equals(batch.getCurrency())));
    }

    @Test
    void settle_nullCurrencyOrders_shouldDefaultToNEX() {
        ClearingOrder o = pendingOrder(null, "100");
        when(eventCollector.drainStaging()).thenReturn(List.of(o));
        when(clearingEngine.batchClear(any(SettlementBatch.class))).thenAnswer(inv -> {
            SettlementBatch b = inv.getArgument(0);
            b.setStatus(SettlementBatch.BatchStatus.SETTLED);
            return b;
        });

        scheduler.settle();

        verify(clearingEngine).batchClear(argThat(batch -> "NEX".equals(batch.getCurrency())));
    }
}
