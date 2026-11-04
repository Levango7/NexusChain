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
 * 板 20261102-0320 钉死用例：结算平台出款地址 fail-closed。
 *
 * <p>核心契约：{@code DefaultClearingEngine} 未拿到 {@code nexus.settlement.platform-address}
 * 时（空串 / 空白），必须在 {@code bookSettlement} 之前拒绝本单——
 * 记 {@code FAILED} 并落库，且<b>不写任何 {@code ledger_entry}</b>。
 * 绝不可回退 {@code "PLATFORM_HOT_WALLET"} 这类占位符：那会被当作真实 from 地址送去签名/广播。</p>
 */
class ClearingEnginePlatformAddressTest {

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
        o.setPayeeAddress("to-addr");
        return o;
    }

    /** 通道判定任意非空地址都可用（等价于 sandbox 默认实现）。 */
    private DefaultClearingEngine engineWithPlatformAddress(String platformAddress) {
        OnChainExecutionChannel channel = new OnChainExecutionChannel() {
            @Override
            public TransactionResult execute(TransactionRequest request) {
                return TransactionResult.success("0xOK", 1, false);
            }

            @Override
            public TransactionResult queryStatus(String txHash) {
                return TransactionResult.success(txHash, 1, false);
            }
        };
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, null, channel, platformAddress);
        engine.setClearingOrderRepository(repository);
        return engine;
    }

    @Test
    @DisplayName("平台地址未配置（空串）：记账之前 fail-closed，FAILED 且不写 ledger_entry")
    void settle_platformAddressMissingFailsClosedBeforeBooking() {
        ClearingOrder result = engineWithPlatformAddress("").settle(order("O_no_platform"));

        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
        assertEquals(0, ledger.entriesOf("MERCHANT:M001").size(),
                "平台地址未配置必须在记账之前拒绝，绝不留下 ledger_entry");
        verify(repository, times(1)).save(result);
    }

    @Test
    @DisplayName("平台地址为空白串：同样 fail-closed，不回退占位符")
    void settle_platformAddressBlankFailsClosedBeforeBooking() {
        ClearingOrder result = engineWithPlatformAddress("   ").settle(order("O_blank_platform"));

        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
        assertEquals(0, ledger.entriesOf("MERCHANT:M001").size(),
                "空白串经 trim 后按未配置处理，不得回退 PLATFORM_HOT_WALLET");
        verify(repository, times(1)).save(result);
    }

    @Test
    @DisplayName("平台地址非空但通道判定不可用：fail-closed，不记账")
    void settle_platformAddressUnusableFailsClosedBeforeBooking() {
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
                // 模拟 gateway 端"非空但解析失败"的判定
                return false;
            }
        };
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, null, channel, "PLATFORM");
        engine.setClearingOrderRepository(repository);

        ClearingOrder result = engine.settle(order("O_unusable_platform"));

        assertEquals(ClearingOrder.OrderStatus.FAILED, result.getStatus());
        assertEquals(0, ledger.entriesOf("MERCHANT:M001").size(),
                "平台地址不可解析同样必须在记账之前拒绝");
        verify(repository, times(1)).save(result);
    }

    @Test
    @DisplayName("平台地址可用：先记账再动链，请求 from 地址来自配置")
    void settle_platformAddressConfiguredBooksAndUsesConfiguredFrom() {
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
        DefaultClearingEngine engine = new DefaultClearingEngine(ledger, null, channel, "PLATFORM_WALLET");
        engine.setClearingOrderRepository(repository);

        ClearingOrder result = engine.settle(order("O_ok_platform"));

        assertEquals(ClearingOrder.OrderStatus.SETTLED, result.getStatus());
        assertEquals(1, ledger.entriesOf("MERCHANT:M001").size());
        assertEquals("PLATFORM_WALLET", captured[0].getFromAddress());
    }
}
