package org.nexus.settlement.clearing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link DefaultClearingEngine#batchClear} 的单笔隔离用例（Board #236 §三 的事后收口）。
 *
 * <p>要钉住的事实：{@code SettlementEventCollector.drainStaging()} 已经把这一批
 * {@code PENDING} 行**删除并提交**，{@code SettlementScheduler.settle()} 又只是
 * {@code catch (RuntimeException) → log}。所以批内任何一笔抛异常，若引擎不就地收口，
 * 结果就是"账已贷、链未付、订单行不存在"——记录静默丢失且永不重试。</p>
 */
class ClearingEngineBatchIsolationTest {

    private Ledger ledger;
    private ClearingOrderRepository repository;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        repository = mock(ClearingOrderRepository.class);
    }

    private static ClearingOrder order(String orderId) {
        ClearingOrder o = new ClearingOrder();
        o.setOrderId(orderId);
        o.setMerchantId("M001");
        o.setAmount(new BigDecimal("100"));
        o.setCurrency("NEX");
        o.setStatus(ClearingOrder.OrderStatus.PENDING);
        // S1（板 20261029-2057/2352）：settle() 现在先用 canTransferTo 预判收款地址，
        // 默认实现要求非空；happy-path 用例必须带上可用收款地址，否则会被预检误判 FAILED。
        o.setPayeeAddress("PAYEE-VALID-M001");
        return o;
    }

    /** 第一笔抛异常（模拟未被通道兜住的异常），其余笔正常成功。 */
    private DefaultClearingEngine engineThrowingOn(List<String> orderIdsToThrow) {
        OnChainExecutionChannel channel = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                if (orderIdsToThrow.contains(request.getRequestId())) {
                    throw new IllegalStateException("simulated channel blow-up");
                }
                return TransactionResult.success("0x" + request.getRequestId(), 1, false);
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return TransactionResult.success(txHash, 1, false);
            }
        };
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, null, channel, "PLATFORM");
        engine.setClearingOrderRepository(repository);
        return engine;
    }

    private static SettlementBatch batchOf(ClearingOrder... orders) {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchNo("B1");
        batch.setOrders(List.of(orders));
        return batch;
    }

    @Test
    @DisplayName("批内单笔抛异常：该笔落 FAILED 并持久化，其余笔照常结算，整批不抛")
    void throwingOrderIsPersistedAndBatchContinues() {
        ClearingOrder bad = order("O_bad");
        ClearingOrder good = order("O_good");
        good.setAmount(new BigDecimal("250"));
        DefaultClearingEngine engine = engineThrowingOn(List.of("O_bad"));

        SettlementBatch result = assertDoesNotThrow(() ->
                engine.batchClear(batchOf(bad, good)));

        assertEquals(ClearingOrder.OrderStatus.FAILED, bad.getStatus(),
                "抛异常的那笔必须有终态记录，不能凭空消失");
        assertEquals(ClearingOrder.OrderStatus.SETTLED, good.getStatus());
        // 净额只认已结算的一笔（不因异常笔而中断累计）
        assertEquals(0, new BigDecimal("250").compareTo(result.getSettlementAmount()));
        verify(repository, times(1)).save(bad);
        verify(repository, times(1)).save(good);
    }

    @Test
    @DisplayName("异常笔不重复记账：一笔订单在本批内只贷一次")
    void abnormalOrderIsBookedExactlyOnce() {
        // 记账发生在动链之前（既有账务语义，#236 §二 裁决"本批不改"）。
        // 钉住当前真实形状：抛异常的那笔也已贷账一次、且本批内不会第二次贷账。
        // 注意 DB 模式下还有 ledger_entry 的 (reference, account) 唯一键兜底（撞键即抛错回滚），
        // 而内存模式（jdbcTemplate==null）**没有**这层保护 ⇒ 任何"重跑同一订单"的改动在
        // 测试/无库环境里会静默多贷，这也是本批不给 settle() 加自动重试的原因。
        ClearingOrder bad = order("O_bad2");
        engineThrowingOn(List.of("O_bad2")).batchClear(batchOf(bad));

        assertEquals(1, ledger.entriesOf("MERCHANT:M001").size());
    }

    @Test
    @DisplayName("直接调用 settle() 的契约不变：异常仍向上抛（隔离在批量层）")
    void directSettleStillPropagates() {
        DefaultClearingEngine engine = engineThrowingOn(List.of("O_direct"));
        assertThrows(IllegalStateException.class, () -> engine.settle(order("O_direct")));
    }
}
