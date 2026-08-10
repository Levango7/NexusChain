package org.nexus.settlement.clearing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.reconciliation.DefaultReconciliationService;
import org.nexus.settlement.reconciliation.InMemoryBankRecordSource;
import org.nexus.settlement.reconciliation.InMemoryChainRecordSource;
import org.nexus.settlement.reconciliation.ReconciliationReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link DefaultClearingEngine} 单元测试。
 */
class DefaultClearingEngineTest {

    private Ledger ledger;
    private DefaultClearingEngine engine;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        var chainSource = new InMemoryChainRecordSource();
        var bankSource = new InMemoryBankRecordSource();
        var reconciliation = new DefaultReconciliationService(ledger, chainSource, bankSource);
        engine = new DefaultClearingEngine(ledger, reconciliation);
    }

    @Test
    void batchClear_shouldSettleOrdersAndComputeNet() {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchNo("B-1");
        batch.setOrders(List.of(
                order("O1", "M001", "100"),
                order("O2", "M001", "50"),
                order("O3", "M002", "30")));

        SettlementBatch result = engine.batchClear(batch);

        assertEquals(SettlementBatch.BatchStatus.SETTLED, result.getStatus());
        assertEquals(new BigDecimal("180"), result.getSettlementAmount());
        assertEquals(new BigDecimal("150"), ledger.balanceOf("MERCHANT:M001"));
        assertEquals(new BigDecimal("30"), ledger.balanceOf("MERCHANT:M002"));
    }

    @Test
    void batchClear_emptyBatch_shouldFail() {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchNo("B-EMPTY");
        batch.setOrders(List.of());

        SettlementBatch result = engine.batchClear(batch);

        assertEquals(SettlementBatch.BatchStatus.FAILED, result.getStatus());
    }

    @Test
    void settle_alreadySettled_shouldBeIdempotent() {
        ClearingOrder o = order("O1", "M001", "100");
        engine.settle(o);
        BigDecimal balanceAfterFirst = ledger.balanceOf("MERCHANT:M001");

        engine.settle(o);

        assertEquals(balanceAfterFirst, ledger.balanceOf("MERCHANT:M001"));
    }

    @Test
    void settle_missingAmount_shouldFail() {
        ClearingOrder o = new ClearingOrder();
        o.setOrderId("O-X");
        o.setMerchantId("M001");
        o.setStatus(ClearingOrder.OrderStatus.PENDING);

        ClearingOrder result = engine.settle(o);

        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    void reconcile_withMatchingChainRecords_shouldBeClean() {
        // 结算一笔订单
        engine.settle(order("O1", "M001", "100"));

        // 对账：无链上记录时，本地有而外部无 → 1 条差错
        ReconciliationReport report = engine.reconcile(null);

        assertNotNull(report);
        assertEquals(1, report.getDiscrepancyCount());
    }

    private ClearingOrder order(String id, String merchant, String amount) {
        ClearingOrder o = new ClearingOrder();
        o.setOrderId(id);
        o.setMerchantId(merchant);
        o.setAmount(new BigDecimal(amount));
        o.setCurrency("USDT");
        o.setStatus(ClearingOrder.OrderStatus.PENDING);
        o.setCreatedAt(Instant.now());
        return o;
    }
}
