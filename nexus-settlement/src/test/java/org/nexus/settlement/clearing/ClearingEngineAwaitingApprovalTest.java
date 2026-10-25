package org.nexus.settlement.clearing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultClearingEngine} 的大额签名审批执行腿用例（Board 裁决 20261025-0415 §六）。
 *
 * <p>要钉死两件事：</p>
 * <ol>
 *   <li>待审批 / 结果不可核对<b>都不是失败</b>，且不能留在 {@code PENDING}
 *       ——{@code drainStaging()} 每轮重捞 PENDING 再跑一次 settle()，
 *       而 {@code bookSettlement} 无去重 ⇒ 重驱就是重复贷账；</li>
 *   <li>审批通过后由推进器收口，只写状态与哈希，<b>不再动账</b>。</li>
 * </ol>
 */
class ClearingEngineAwaitingApprovalTest {

    private Ledger ledger;
    private ClearingOrderRepository repository;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        repository = mock(ClearingOrderRepository.class);
    }

    private static ClearingOrder order(String orderId, String merchantId, String amount) {
        ClearingOrder o = new ClearingOrder();
        o.setOrderId(orderId);
        o.setMerchantId(merchantId);
        o.setAmount(new BigDecimal(amount));
        o.setCurrency("NEX");
        o.setStatus(ClearingOrder.OrderStatus.PENDING);
        return o;
    }

    private DefaultClearingEngine engineReturning(TransactionResult result) {
        OnChainExecutionChannel channel = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                return result;
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return result;
            }
        };
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, null, channel, "PLATFORM");
        engine.setClearingOrderRepository(repository);
        return engine;
    }

    @Test
    @DisplayName("2001 待审批→AWAITING（不是 FAILED、不是 PENDING）并落库一次")
    void settle_awaitingApprovalIsNotFailure() {
        ClearingOrder result = engineReturning(TransactionResult.awaitingApproval("appr-1"))
                .settle(order("O1", "M001", "100"));

        assertEquals(ClearingOrder.OrderStatus.AWAITING, result.getStatus());
        assertNull(result.getSettlementTxHash(), "未动账不得有结算哈希");
        verify(repository).save(result);
        // 落账仍只发生一次（在动链之前，本切片不改记账顺序，见板子留言 §二第 3 点）
        assertEquals(1, ledger.entriesOf("MERCHANT:M001").size());
    }

    @Test
    @DisplayName("结果不可核对→UNVERIFIED（禁止重试），不记 FAILED")
    void settle_unverifiedIsNotFailure() {
        ClearingOrder result = engineReturning(
                TransactionResult.executedUnverified("appr-2", "no hash"))
                .settle(order("O2", "M001", "100"));

        assertEquals(ClearingOrder.OrderStatus.UNVERIFIED, result.getStatus());
        verify(repository).save(result);
    }

    @Test
    @DisplayName("明确失败仍记 FAILED（只有确定未动账才失败）")
    void settle_explicitFailureMarksFailed() {
        ClearingOrder result = engineReturning(TransactionResult.failure("chain error", false))
                .settle(order("O3", "M001", "100"));

        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
    }

    @Test
    @DisplayName("待审批/待核对都不计入批次净额（净额只认 SETTLED）")
    void batchClear_excludesNonSettledOrders() {
        SettlementBatch batch = new SettlementBatch();
        batch.setBatchNo("B1");
        batch.setOrders(java.util.List.of(
                order("O4", "M001", "100"),
                order("O5", "M001", "200")));
        engineReturning(TransactionResult.awaitingApproval("appr-3")).batchClear(batch);

        assertEquals(BigDecimal.ZERO, batch.getSettlementAmount(),
                "两笔都待审批 ⇒ 本批不应有任何已结算净额");
    }

    @Test
    @DisplayName("收口：AWAITING→SETTLED + 回填哈希，且不再落账")
    void completeAfterApproval_transitionsAndDoesNotRebook() {
        ClearingOrder stored = order("O6", "M001", "100");
        stored.setStatus(ClearingOrder.OrderStatus.AWAITING);
        when(repository.findById("O6")).thenReturn(Optional.of(stored));

        DefaultClearingEngine engine = engineReturning(TransactionResult.success("0xX", 1, false));
        assertTrue(engine.completeAfterSigningApproval("O6", "0xSettle"));

        assertEquals(ClearingOrder.OrderStatus.SETTLED, stored.getStatus());
        assertEquals("0xSettle", stored.getSettlementTxHash());
        verify(repository).save(stored);
        // 关键：收口路径一次都不碰账本（settle() 未被调用）
        assertEquals(0, ledger.entriesOf("MERCHANT:M001").size());
    }

    @Test
    @DisplayName("收口幂等：已 SETTLED 的单不被再次改写，也不报错")
    void completeAfterApproval_skipsAlreadySettled() {
        ClearingOrder stored = order("O7", "M001", "100");
        stored.setStatus(ClearingOrder.OrderStatus.SETTLED);
        stored.setSettlementTxHash("0xFirst");
        when(repository.findById("O7")).thenReturn(Optional.of(stored));

        assertFalse(engineReturning(TransactionResult.awaitingApproval("a"))
                .completeAfterSigningApproval("O7", "0xSecond"));

        assertEquals("0xFirst", stored.getSettlementTxHash());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("EXECUTED 但缺哈希→转 UNVERIFIED，绝不冒充 SETTLED")
    void completeAfterApproval_withoutHashGoesUnverified() {
        ClearingOrder stored = order("O8", "M001", "100");
        stored.setStatus(ClearingOrder.OrderStatus.AWAITING);
        when(repository.findById("O8")).thenReturn(Optional.of(stored));

        assertFalse(engineReturning(TransactionResult.awaitingApproval("a"))
                .completeAfterSigningApproval("O8", "  "));

        assertEquals(ClearingOrder.OrderStatus.UNVERIFIED, stored.getStatus());
        assertNull(stored.getSettlementTxHash());
    }

    @Test
    @DisplayName("审批被拒（确定未动账）→FAILED")
    void rejectedMarksFailed() {
        ClearingOrder stored = order("O9", "M001", "100");
        stored.setStatus(ClearingOrder.OrderStatus.AWAITING);
        when(repository.findById("O9")).thenReturn(Optional.of(stored));

        assertTrue(engineReturning(TransactionResult.awaitingApproval("a"))
                .markSigningApprovalRejected("O9", "审批被拒"));

        assertEquals(ClearingOrder.OrderStatus.FAILED, stored.getStatus());
    }

    @Test
    @DisplayName("仓储未装配（纯内存路径）时收口返回 false，不凭空改状态")
    void completionWithoutRepositoryIsNoOp() {
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, null,
                new OnChainExecutionChannel() {
                    @Override
                    public TransactionResult execute(TransactionRequest request) {
                        return TransactionResult.awaitingApproval("appr-x");
                    }

                    @Override
                    public TransactionResult queryStatus(String txHash) {
                        return TransactionResult.awaitingApproval("appr-x");
                    }
                }, "PLATFORM");

        assertFalse(engine.completeAfterSigningApproval("O10", "0xHash"));
        assertFalse(engine.markSigningApprovalRejected("O10", "reason"));
        assertFalse(engine.markSigningApprovalUnverified("O10", "reason"));
        verify(repository, never()).findById(anyString());
    }
}
