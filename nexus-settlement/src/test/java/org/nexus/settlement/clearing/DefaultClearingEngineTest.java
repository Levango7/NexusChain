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
 *
 * <p>Path C 扩展：settlementTxHash 回填断言 + 结算→回填→对账匹配闭环验证。</p>
 */
class DefaultClearingEngineTest {

    private Ledger ledger;
    private InMemoryChainRecordSource chainSource;
    private DefaultClearingEngine engine;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        chainSource = new InMemoryChainRecordSource();
        var bankSource = new InMemoryBankRecordSource();
        var reconciliation = new DefaultReconciliationService(ledger, chainSource, bankSource);
        engine = new DefaultClearingEngine(ledger, reconciliation);
        // Path C：注入回填型链上记录源，形成结算→链上凭证→对账匹配闭环
        engine.setChainRecordSource(chainSource);
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
    void reconcile_withoutFeedableSource_shouldReportLocalOnlyDiscrepancy() {
        // 未注入回填型链上记录源（chainRecordSource = null）的场景：
        // 结算成功但链上记录源无记录 → 对账报告 1 条「本地有、外部无」差错。
        // 这验证了 Path C 修复前旧行为的成因——链上记录断链。
        DefaultClearingEngine isolatedEngine = new DefaultClearingEngine(
                ledger,
                new org.nexus.settlement.reconciliation.DefaultReconciliationService(
                        ledger, new InMemoryChainRecordSource(), new InMemoryBankRecordSource()));

        isolatedEngine.settle(order("O1", "M001", "100"));

        ReconciliationReport report = isolatedEngine.reconcile(null);

        assertNotNull(report);
        assertEquals(1, report.getDiscrepancyCount());
    }

    // === Path C 新增：settlementTxHash 回填 + 闭环验证 ===

    @Test
    void settle_success_shouldBackfillSettlementTxHash() {
        ClearingOrder result = engine.settle(order("O1", "M001", "100"));

        assertEquals(ClearingOrder.OrderStatus.SETTLED, result.getStatus());
        assertNotNull(result.getSettlementTxHash());
    }

    @Test
    void settle_failed_shouldNotBackfillTxHash() {
        ClearingOrder o = order("O1", "M001", "100");
        o.setAmount(null);
        o.setStatus(ClearingOrder.OrderStatus.PENDING);

        ClearingOrder result = engine.settle(o);

        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
        assertEquals(null, result.getSettlementTxHash());
    }

    @Test
    void settle_withFeedableSource_shouldCloseReconciliationLoop() {
        // 结算 → 回填链上凭证 → 对账应全匹配（闭环，不再虚假差错）
        engine.settle(order("O1", "M001", "100"));

        ReconciliationReport report = engine.reconcile(null);

        assertNotNull(report);
        assertEquals(1, report.getMatchedCount());
        assertEquals(0, report.getDiscrepancyCount());
    }

    @Test
    void batchClear_withFeedableSource_allOrdersShouldMatch() {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchNo("B-LOOP");
        batch.setOrders(List.of(
                order("O1", "M001", "100"),
                order("O2", "M002", "30")));

        engine.batchClear(batch);

        ReconciliationReport report = engine.reconcile(null);

        assertEquals(2, report.getMatchedCount());
        assertEquals(0, report.getDiscrepancyCount());
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
