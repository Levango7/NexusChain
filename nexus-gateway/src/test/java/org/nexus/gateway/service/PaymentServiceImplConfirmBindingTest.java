package org.nexus.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.client.OnChainTransaction;
import org.nexus.gateway.compliance.AmlResult;
import org.nexus.gateway.compliance.ComplianceService;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.dto.PaymentResult;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.repository.RefundRepository;
import org.nexus.gateway.risk.PaymentRiskService;
import org.nexus.gateway.security.KeyManager;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.nexus.sdk.wallet.WalletUtils;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link PaymentServiceImpl#confirmPayment} 交易-订单绑定校验（P0-5）单元测试。
 *
 * <p>覆盖：金额不匹配、收款人不匹配、txHash 已被其他订单占用、
 * 链节点不返回交易详情时的降级路径，以及完整校验通过的正常路径。
 * 同时固化安全加固行为：绑定校验失败路径不得持久化攻击者可控的 txHash。</p>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceImplConfirmBindingTest {

    private static final String TX_HASH = "0x" + "a".repeat(60);

    @Mock private PaymentOrderRepository orderRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private GatewayConfig gatewayConfig;
    @Mock private GatewayConfig.ChainConfig chainConfig;
    @Mock private ChainRpcClient chainRpcClient;
    @Mock private SigningServiceFeignClient signingServiceClient;
    @Mock private WalletMgmtFeignClient walletMgmtClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private KeyManager keyManager;
    @Mock private PaymentRiskService riskService;
    @Mock private ComplianceService complianceService;

    private PaymentServiceImpl service;
    private PaymentOrder order;

    @BeforeEach
    void setUp() {
        service = new PaymentServiceImpl(orderRepository, refundRepository, gatewayConfig,
                chainRpcClient, signingServiceClient, walletMgmtClient, eventPublisher,
                keyManager, riskService, complianceService);

        lenient().when(gatewayConfig.getChain()).thenReturn(chainConfig);
        lenient().when(chainConfig.isSkipConfirmation()).thenReturn(false);

        order = new PaymentOrder();
        order.setId(1L);
        order.setOrderNo("NEX-BIND-001");
        order.setMerchantId(100L);
        order.setAmount(new BigDecimal("1000000"));
        order.setTokenSymbol("NEX");
        order.setStatus(PaymentOrder.OrderStatus.PAYING);
        order.setPayerAddress("NXPAYERADDR");
        order.setPayeeAddress("NXPAYEEADDR");

        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private OnChainTransaction onChainTx(BigDecimal amount, String recipient) {
        return new OnChainTransaction(TX_HASH, amount, "NEX", "NXPAYERADDR", recipient, true, 12L);
    }

    private void chainConfirmed() {
        when(chainRpcClient.isTransactionConfirmed(TX_HASH)).thenReturn(true);
    }

    @Test
    @DisplayName("金额不匹配：订单置 FAILED，且不得持久化 txHash")
    void confirm_amountMismatchFailsAndDoesNotPersistTxHash() {
        chainConfirmed();
        when(chainRpcClient.getTransaction(TX_HASH)).thenReturn(onChainTx(new BigDecimal("1"), "NXPAYEEADDR"));

        PaymentResult result = service.confirmPayment(1L, TX_HASH);

        assertEquals("FAILED", result.getStatus());
        assertEquals(PaymentOrder.OrderStatus.FAILED, order.getStatus());
        assertNull(order.getChainTxHash(), "失败路径不得写入 txHash（防止抢占唯一约束槽位）");
        verify(complianceService, never()).screenAml(any());
    }

    @Test
    @DisplayName("收款人不匹配：订单置 FAILED，且不得持久化 txHash")
    void confirm_recipientMismatchFailsAndDoesNotPersistTxHash() {
        chainConfirmed();
        when(chainRpcClient.getTransaction(TX_HASH))
                .thenReturn(onChainTx(new BigDecimal("1000000"), "ATTACKER-ADDR"));

        PaymentResult result = service.confirmPayment(1L, TX_HASH);

        assertEquals("FAILED", result.getStatus());
        assertEquals(PaymentOrder.OrderStatus.FAILED, order.getStatus());
        assertNull(order.getChainTxHash());
        verify(complianceService, never()).screenAml(any());
    }

    @Test
    @DisplayName("txHash 已被其他订单占用：拒绝确认（防一笔交易确认多单）")
    void confirm_duplicateTxHashRejected() {
        chainConfirmed();
        PaymentOrder other = new PaymentOrder();
        other.setId(2L);
        other.setOrderNo("NEX-OTHER");
        when(orderRepository.findByChainTxHash(TX_HASH)).thenReturn(Optional.of(other));

        PaymentResult result = service.confirmPayment(1L, TX_HASH);

        assertEquals("FAILED", result.getStatus());
        assertEquals(PaymentOrder.OrderStatus.FAILED, order.getStatus());
        assertNull(order.getChainTxHash());
        verify(chainRpcClient, never()).getTransaction(anyString());
        verify(complianceService, never()).screenAml(any());
    }

    @Test
    @DisplayName("交易详情可用且匹配：正常置 PAID 并绑定 txHash")
    void confirm_matchingTransactionMarksPaid() {
        chainConfirmed();
        when(orderRepository.findByChainTxHash(TX_HASH)).thenReturn(Optional.empty());
        when(chainRpcClient.getTransaction(TX_HASH))
                .thenReturn(onChainTx(new BigDecimal("1000000"), "NXPAYEEADDR"));
        when(complianceService.screenAml(any())).thenReturn(new AmlResult());

        try (MockedStatic<WalletUtils> mockedWalletUtils = mockStatic(WalletUtils.class)) {
            // P0-5 修复后 confirmPayment 用 WalletUtils.addressToPubkeyHash 将 payee 地址转为 hash，
            // 再与链上交易 recipient（pubkey hash）比较。单测中 mock 使 NXPAYEEADDR → NXPAYEEADDR。
            mockedWalletUtils.when(() -> WalletUtils.addressToPubkeyHash("NXPAYEEADDR")).thenReturn("NXPAYEEADDR");

            PaymentResult result = service.confirmPayment(1L, TX_HASH);

            assertEquals("PAID", result.getStatus());
            assertEquals(PaymentOrder.OrderStatus.PAID, order.getStatus());
            assertEquals(TX_HASH, order.getChainTxHash());
            assertNotNull(order.getPaidAt());
        }
    }

    @Test
    @DisplayName("链节点不返回交易详情：降级为长度+唯一性校验并告警后放行（当前已知降级语义）")
    void confirm_detailsUnavailableFallsBackToLengthAndUniqueness() {
        chainConfirmed();
        when(orderRepository.findByChainTxHash(TX_HASH)).thenReturn(Optional.empty());
        when(chainRpcClient.getTransaction(TX_HASH)).thenReturn(null);
        when(complianceService.screenAml(any())).thenReturn(new AmlResult());

        PaymentResult result = service.confirmPayment(1L, TX_HASH);

        // 固化当前降级行为：放行并标记 PAID。生产环境应配置要求交易详情（后续加固项）。
        assertEquals("PAID", result.getStatus());
        assertEquals(PaymentOrder.OrderStatus.PAID, order.getStatus());
        assertEquals(TX_HASH, order.getChainTxHash());
    }
}
