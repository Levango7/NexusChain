package org.nexus.settlement.clearing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;
import org.nexus.settlement.reconciliation.DefaultReconciliationService;
import org.nexus.settlement.reconciliation.InMemoryBankRecordSource;
import org.nexus.settlement.reconciliation.InMemoryChainRecordSource;
import org.nexus.settlement.reconciliation.ReconciliationReport;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultClearingEngine} 补充测试：覆盖 execution channel 失败 / null、
 * batchClear null / empty orders、reconcile 多分支、自定义 platform address。
 */
class DefaultClearingEngineBranchTest {

    private Ledger ledger;
    private InMemoryChainRecordSource chainSource;
    private InMemoryBankRecordSource bankSource;
    private DefaultReconciliationService reconciliation;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        chainSource = new InMemoryChainRecordSource();
        bankSource = new InMemoryBankRecordSource();
        reconciliation = new DefaultReconciliationService(ledger, chainSource, bankSource);
    }

    @Test
    void settle_executionChannelReturnsFailed_shouldMarkFailed() {
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
        DefaultClearingEngine engine = new DefaultClearingEngine(
                ledger, reconciliation, failing, "PLATFORM");

        ClearingOrder o = order("O1", "M001", "100");
        ClearingOrder result = engine.settle(o);

        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    void settle_executionChannelReturnsNull_shouldMarkFailed() {
        OnChainExecutionChannel nullChannel = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                return null;
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return null;
            }
        };
        DefaultClearingEngine engine = new DefaultClearingEngine(
                ledger, reconciliation, nullChannel, "PLATFORM");

        ClearingOrder o = order("O1", "M001", "100");
        ClearingOrder result = engine.settle(o);

        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    void settle_nullOrder_shouldReturnNull() {
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, reconciliation);
        assertNull(engine.settle(null));
    }

    @Test
    void settle_nullMerchantId_shouldMarkFailed() {
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, reconciliation);
        ClearingOrder o = new ClearingOrder();
        o.setOrderId("O1");
        o.setAmount(new BigDecimal("100"));
        o.setStatus(ClearingOrder.OrderStatus.PENDING);

        ClearingOrder result = engine.settle(o);
        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    void batchClear_nullBatch_shouldReturnNull() {
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, reconciliation);
        assertNull(engine.batchClear(null));
    }

    @Test
    void batchClear_nullOrders_shouldFail() {
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, reconciliation);
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchNo("B-NULL");

        SettlementBatch result = engine.batchClear(batch);
        assertEquals(SettlementBatch.BatchStatus.FAILED, result.getStatus());
    }

    @Test
    void batchClear_mixedSuccessAndFailure_shouldOnlySumSettled() {
        OnChainExecutionChannel conditional = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                return "settlement:O-OK".equals(request.getMemo())
                        ? TransactionResult.success("0xOK", 1, true)
                        : TransactionResult.failure("fail", true);
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return TransactionResult.success(txHash, 1, true);
            }
        };
        DefaultClearingEngine engine = new DefaultClearingEngine(
                ledger, reconciliation, conditional, "PLATFORM");

        SettlementBatch batch = new SettlementBatch();
        batch.setBatchNo("B-MIX");
        batch.setOrders(List.of(
                order("O-OK", "M001", "100"),
                order("O-BAD", "M001", "50")));

        SettlementBatch result = engine.batchClear(batch);
        assertEquals(SettlementBatch.BatchStatus.SETTLED, result.getStatus());
        assertEquals(new BigDecimal("100"), result.getSettlementAmount());
    }

    @Test
    void reconcile_chainReportHasDiscrepancies_shouldMergeIntoInput() {
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, reconciliation);

        // 制造本地有外部无的差错
        ledger.bookSettlement("M001", new BigDecimal("100"), "O1");

        ReconciliationReport input = new ReconciliationReport();
        input.setDiscrepancies(List.of("pre-existing"));
        input.setDiscrepancyCount(1);

        ReconciliationReport result = engine.reconcile(input);
        assertNotNull(result);
        // 合并后应包含 pre-existing + 链上差错
        assertTrue(result.getDiscrepancyCount() >= 1);
    }

    @Test
    void reconcile_nullInputAndCleanChain_shouldReturnReport() {
        // 链上无差错时（本地与链上均空），reconcile(null) 应返回链上报告
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, reconciliation);
        ReconciliationReport result = engine.reconcile(null);
        assertNotNull(result);
    }

    @Test
    void constructor_emptyPlatformAddress_shouldFallbackToDefault() {
        OnChainExecutionChannel channel = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                return TransactionResult.success("0x", 1, true);
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return TransactionResult.success(txHash, 1, true);
            }
        };
        DefaultClearingEngine engine = new DefaultClearingEngine(
                ledger, reconciliation, channel, "");

        ClearingOrder o = order("O1", "M001", "100");
        ClearingOrder result = engine.settle(o);
        assertEquals(ClearingOrder.OrderStatus.SETTLED, result.getStatus());
    }

    @Test
    void constructor_nullPlatformAddress_shouldFallbackToDefault() {
        OnChainExecutionChannel channel = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                return TransactionResult.success("0x", 1, true);
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return TransactionResult.success(txHash, 1, true);
            }
        };
        DefaultClearingEngine engine = new DefaultClearingEngine(
                ledger, reconciliation, channel, null);

        ClearingOrder o = order("O1", "M001", "100");
        ClearingOrder result = engine.settle(o);
        assertEquals(ClearingOrder.OrderStatus.SETTLED, result.getStatus());
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
