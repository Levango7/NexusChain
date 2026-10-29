package org.nexus.settlement.clearing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * S1（板 20261029-2057 裁决 / 20261029-2352 范围）收款地址预检的钉死用例。
 *
 * <p>核心契约：{@code DefaultClearingEngine.settle()} 必须在 {@code bookSettlement}
 * （不可回滚的记账副作用，走独立事务当场提交）之前，用
 * {@code executionChannel.canTransferTo(payeeAddress)} 预判收款地址是否可用；
 * 不可用则记 {@code FAILED} 并落库，且<b>不写任何 {@code ledger_entry}</b>
 * （fail-closed before booking）。</p>
 *
 * <p>历史缺陷：结算引擎曾把 {@code "MERCHANT:" + merchantId} 当收款地址传入，该串含
 * {@code ':'}，不在 Base58 字母表 ⇒ 解析必失败；而记账发生在动链之前，于是留下
 * 「已贷账、必失败」的永久偏移（FAILED 终态不可重试、无冲正）。S1 把判定前移。</p>
 */
class ClearingEnginePayeeAddressS1Test {

    private Ledger ledger;
    private ClearingOrderRepository repository;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        repository = mock(ClearingOrderRepository.class);
    }

    private static ClearingOrder orderWithPayee(String orderId, String payeeAddress) {
        ClearingOrder o = new ClearingOrder();
        o.setOrderId(orderId);
        o.setMerchantId("M001");
        o.setAmount(new BigDecimal("100"));
        o.setCurrency("NEX");
        o.setStatus(ClearingOrder.OrderStatus.PENDING);
        o.setPayeeAddress(payeeAddress);
        return o;
    }

    /** 一个"地址可用"的通道：canTransferTo 走接口默认实现（非空即 true）。 */
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
    @DisplayName("S1-a 收款地址为 null：记账之前 fail-closed，FAILED 且不写 ledger_entry")
    void settle_missingPayeeAddressFailsClosedBeforeBooking() {
        ClearingOrder o = orderWithPayee("O_null", null);
        ClearingOrder result = engineReturning(TransactionResult.success("0xOK", 1, false)).settle(o);

        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
        assertEquals(0, ledger.entriesOf("MERCHANT:M001").size(),
                "fail-closed 必须在记账之前，绝不留下 ledger_entry");
        verify(repository, times(1)).save(result);
    }

    @Test
    @DisplayName("S1-b 收款地址为空白串：同样 fail-closed，不写 ledger_entry")
    void settle_blankPayeeAddressFailsClosedBeforeBooking() {
        ClearingOrder o = orderWithPayee("O_blank", "   ");
        ClearingOrder result = engineReturning(TransactionResult.success("0xOK", 1, false)).settle(o);

        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
        assertEquals(0, ledger.entriesOf("MERCHANT:M001").size());
        verify(repository, times(1)).save(result);
    }

    @Test
    @DisplayName("S1-c 通道判定地址不可用（非空但无效）：fail-closed，不写 ledger_entry")
    void settle_channelRejectsAddressFailsClosedBeforeBooking() {
        OnChainExecutionChannel channel = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                return TransactionResult.success("0xOK", 1, false);
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return TransactionResult.success(txHash, 1, false);
            }

            @Override
            public boolean canTransferTo(String toAddress) {
                // 模拟 gateway 端"地址非空但解析失败"的判定
                return false;
            }
        };
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, null, channel, "PLATFORM");
        engine.setClearingOrderRepository(repository);

        // 非空但通道判定不可用
        ClearingOrder o = orderWithPayee("O_reject", "MERCHANT:M001");
        ClearingOrder result = engine.settle(o);

        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
        assertEquals(0, ledger.entriesOf("MERCHANT:M001").size(),
                "即便地址非空，只要通道判定不可用就必须 fail-closed，不记账");
        verify(repository, times(1)).save(result);
    }

    @Test
    @DisplayName("S1-d 地址可用时：仍先记账再动链，且收款地址进入 TransactionRequest.toAddress")
    void settle_validPayeeAddressBooksBeforeChainAndFlowsToRequest() {
        final TransactionRequest[] captured = {null};
        OnChainExecutionChannel channel = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                captured[0] = request;
                return TransactionResult.success("0xOK", 1, false);
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return TransactionResult.success(txHash, 1, false);
            }
        };
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, null, channel, "PLATFORM");
        engine.setClearingOrderRepository(repository);

        ClearingOrder o = orderWithPayee("O_ok", "PAYEE-VALID-1");
        ClearingOrder result = engine.settle(o);

        assertEquals(ClearingOrder.OrderStatus.SETTLED, result.getStatus());
        assertEquals(1, ledger.entriesOf("MERCHANT:M001").size(),
                "地址可用时记账仍发生在动链之前（S1 不改记账顺序）");
        // 关键：结算不再使用合成账户名 "MERCHANT:" + merchantId，而是清空真实的 payeeAddress
        assertEquals("PAYEE-VALID-1", captured[0].getToAddress(),
                "TransactionRequest.toAddress 必须是真实收款地址，而非 'MERCHANT:' 合成名");
    }
}
