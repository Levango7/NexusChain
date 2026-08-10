package org.nexus.settlement.funds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.clearing.Ledger;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DefaultFundSweepService} 补充测试：覆盖 null 入参、已结算订单、
 * execution channel 失败 / null / 抛异常、autoSweep 空队列、自定义阈值等分支。
 */
class DefaultFundSweepServiceBranchTest {

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
    }

    @Test
    void sweep_nullOrder_shouldReturnNull() {
        DefaultFundSweepService service = new DefaultFundSweepService(ledger);
        assertNull(service.sweep(null));
    }

    @Test
    void sweep_alreadySettled_shouldBeIdempotent() {
        DefaultFundSweepService service = new DefaultFundSweepService(ledger);
        CollectionOrder o = newOrder("SW-1", "A", "B", "100");
        o.setStatus(CollectionOrder.OrderStatus.SETTLED);

        CollectionOrder result = service.sweep(o);
        assertEquals(CollectionOrder.OrderStatus.SETTLED, result.getStatus());
    }

    @Test
    void sweep_executionChannelFails_shouldMarkFailed() {
        OnChainExecutionChannel failing = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                return TransactionResult.failure("chain error", false);
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return TransactionResult.failure("n/a", false);
            }
        };
        DefaultFundSweepService service = new DefaultFundSweepService(
                ledger, new BigDecimal("10000"), failing);

        CollectionOrder o = newOrder("SW-1", "A", "B", "100");
        CollectionOrder result = service.sweep(o);
        assertEquals(CollectionOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    void sweep_executionChannelReturnsNull_shouldMarkFailed() {
        OnChainExecutionChannel nullChan = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                return null;
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return null;
            }
        };
        DefaultFundSweepService service = new DefaultFundSweepService(
                ledger, new BigDecimal("10000"), nullChan);

        CollectionOrder o = newOrder("SW-1", "A", "B", "100");
        CollectionOrder result = service.sweep(o);
        assertEquals(CollectionOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    void sweep_executionChannelThrows_shouldMarkFailed() {
        OnChainExecutionChannel throwing = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                throw new RuntimeException("network down");
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return null;
            }
        };
        DefaultFundSweepService service = new DefaultFundSweepService(
                ledger, new BigDecimal("10000"), throwing);

        CollectionOrder o = newOrder("SW-1", "A", "B", "100");
        CollectionOrder result = service.sweep(o);
        assertEquals(CollectionOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    void sweep_invalidOrder_missingAmount_shouldFail() {
        DefaultFundSweepService service = new DefaultFundSweepService(ledger);
        CollectionOrder o = new CollectionOrder();
        o.setOrderId("SW-X");
        o.setSourceAddress("A");
        o.setTargetAddress("B");
        o.setStatus(CollectionOrder.OrderStatus.PENDING);

        CollectionOrder result = service.sweep(o);
        assertEquals(CollectionOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    void sweep_invalidOrder_emptySourceAddress_shouldFail() {
        DefaultFundSweepService service = new DefaultFundSweepService(ledger);
        CollectionOrder o = newOrder("SW-X", "", "B", "100");

        CollectionOrder result = service.sweep(o);
        assertEquals(CollectionOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    void sweep_invalidOrder_nullOrderId_shouldFail() {
        DefaultFundSweepService service = new DefaultFundSweepService(ledger);
        CollectionOrder o = newOrder(null, "A", "B", "100");

        CollectionOrder result = service.sweep(o);
        assertEquals(CollectionOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    void enqueue_nullOrder_shouldBeNoOp() {
        DefaultFundSweepService service = new DefaultFundSweepService(ledger);
        service.enqueue(null);
        assertEquals(0, service.autoSweep());
    }

    @Test
    void enqueue_nullStatusAndCreatedAt_shouldDefault() {
        DefaultFundSweepService service = new DefaultFundSweepService(ledger);
        CollectionOrder o = new CollectionOrder();
        o.setOrderId("SW-1");
        o.setSourceAddress("A");
        o.setTargetAddress("B");
        o.setAmount(new BigDecimal("100"));
        o.setCurrency("USDT");
        // status 与 createdAt 均为 null

        service.enqueue(o);
        assertEquals(CollectionOrder.OrderStatus.PENDING, o.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(o.getCreatedAt());
    }

    @Test
    void autoSweep_emptyQueue_shouldReturnZero() {
        DefaultFundSweepService service = new DefaultFundSweepService(ledger);
        assertEquals(0, service.autoSweep());
    }

    @Test
    void autoSweep_mixedPendingAndSettled_shouldOnlyProcessPending() {
        DefaultFundSweepService service = new DefaultFundSweepService(ledger);

        CollectionOrder pending = newOrder("SW-1", "A", "B", "100");
        CollectionOrder settled = newOrder("SW-2", "C", "D", "50");
        settled.setStatus(CollectionOrder.OrderStatus.SETTLED);
        service.enqueue(pending);
        service.enqueue(settled);

        int settledCount = service.autoSweep();
        assertEquals(1, settledCount);
    }

    @Test
    void transferToCold_customThreshold_shouldTriggerAtLowerBalance() {
        DefaultFundSweepService service = new DefaultFundSweepService(
                ledger, new BigDecimal("100"), new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                return TransactionResult.success("0x", 1, true);
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return TransactionResult.success(txHash, 1, true);
            }
        });

        // 热钱包余额 200 >= 自定义阈值 100 → 触发转移
        ledger.bookTransfer("FUNDING", DefaultFundSweepService.HOT_WALLET, new BigDecimal("200"), "SEED");
        int transferred = service.transferToCold();
        assertEquals(1, transferred);
    }

    @Test
    void transferToCold_nullThresholdInConstructor_shouldUseDefault() {
        // 通过两参构造器注入 null 阈值，应回退到默认 10000
        DefaultFundSweepService service = new DefaultFundSweepService(ledger, null);
        ledger.bookTransfer("FUNDING", DefaultFundSweepService.HOT_WALLET, new BigDecimal("5000"), "SEED");

        // 5000 < 10000 → 不触发
        assertEquals(0, service.transferToCold());
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