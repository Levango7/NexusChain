package org.nexus.settlement.funds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.clearing.Ledger;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DefaultFundSweepService} 单元测试。
 */
class DefaultFundSweepServiceTest {

    private Ledger ledger;
    private DefaultFundSweepService service;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        service = new DefaultFundSweepService(ledger);
    }

    @Test
    void sweep_validOrder_shouldSettleAndMoveBalance() {
        // 先给源地址注资（模拟链上到账）
        ledger.bookTransfer("FUNDING", "ADDR_A", new BigDecimal("100"), "SEED");
        CollectionOrder order = newOrder("SW-1", "ADDR_A", "HOT_WALLET", "100");

        CollectionOrder result = service.sweep(order);

        assertEquals(CollectionOrder.OrderStatus.SETTLED, result.getStatus());
        assertEquals(BigDecimal.ZERO, ledger.balanceOf("ADDR_A"));
        assertEquals(new BigDecimal("100"), ledger.balanceOf("HOT_WALLET"));
    }

    @Test
    void sweep_invalidOrder_shouldFail() {
        CollectionOrder order = new CollectionOrder();
        order.setOrderId("SW-BAD");
        // 缺少源/目标地址与金额

        CollectionOrder result = service.sweep(order);

        assertEquals(CollectionOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    void autoSweep_shouldProcessPendingOrders() {
        service.enqueue(newOrder("SW-1", "ADDR_A", "HOT_WALLET", "100"));
        service.enqueue(newOrder("SW-2", "ADDR_B", "HOT_WALLET", "50"));

        int settled = service.autoSweep();

        assertEquals(2, settled);
        assertEquals(new BigDecimal("150"), ledger.balanceOf("HOT_WALLET"));
    }

    @Test
    void transferToCold_belowThreshold_shouldSkip() {
        // 热钱包余额 100 < 阈值 10000
        ledger.bookTransfer("FUNDING", DefaultFundSweepService.HOT_WALLET, new BigDecimal("100"), "SEED");

        int transferred = service.transferToCold();

        assertEquals(0, transferred);
    }

    @Test
    void transferToCold_aboveThreshold_shouldMove() {
        // 热钱包余额 20000 >= 阈值 10000
        ledger.bookTransfer("FUNDING", DefaultFundSweepService.HOT_WALLET, new BigDecimal("20000"), "SEED");

        int transferred = service.transferToCold();

        assertEquals(1, transferred);
        assertEquals(BigDecimal.ZERO, ledger.balanceOf(DefaultFundSweepService.HOT_WALLET));
        assertEquals(new BigDecimal("20000"), ledger.balanceOf(DefaultFundSweepService.COLD_WALLET));
    }

    private CollectionOrder newOrder(String id, String from, String to, String amount) {
        CollectionOrder o = new CollectionOrder();
        o.setOrderId(id);
        o.setSourceAddress(from);
        o.setTargetAddress(to);
        o.setAmount(new BigDecimal(amount));
        o.setCurrency("USDT");
        o.setStatus(CollectionOrder.OrderStatus.PENDING);
        return o;
    }
}
