package org.nexus.gateway.service;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.approval.ApprovalExecutionLink;
import org.nexus.gateway.approval.SigningApprovalExecutionService;
import org.nexus.gateway.approval.SigningOutcome;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.compliance.ComplianceService;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.event.RefundCompletedEvent;
import org.nexus.gateway.execution.ExecutionRequest;
import org.nexus.gateway.execution.OnChainResult;
import org.nexus.gateway.execution.ThreePhaseExecutionTemplate;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.model.Refund;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.gateway.repository.RefundRepository;
import org.nexus.gateway.risk.PaymentRiskService;
import org.nexus.gateway.risk.RiskDecision;
import org.nexus.gateway.security.KeyManager;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.WalletMgmtFeignClient;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PaymentServiceImpl#refund} 三阶段路径在大额签名审批（2001）下的行为。
 *
 * <p>用真实的 {@link ThreePhaseExecutionTemplate} 交互（mock 模板、按其契约依次调用
 * persist/execute/confirm 回调），验证阶段3 的分支：</p>
 * <ul>
 *   <li>2001 → 退款 {@code AWAITING_APPROVAL}，订单保持 REFUND_PENDING，绝不算 FAILED；</li>
 *   <li>已执行但无哈希 → {@code RECONCILIATION_NEEDED}（不自动重试）；</li>
 *   <li>成功 → COMPLETED + 订单 REFUNDED + 完成事件。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceImplRefundApprovalTest {

    @Mock private PaymentOrderRepository orderRepository;
    @Mock private RefundRepository refundRepository;
    @Mock private ChainRpcClient chainRpcClient;
    @Mock private SigningServiceFeignClient signingServiceClient;
    @Mock private WalletMgmtFeignClient walletMgmtClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private KeyManager keyManager;
    @Mock private PaymentRiskService riskService;
    @Mock private ComplianceService complianceService;
    @Mock private ThreePhaseExecutionTemplate threePhaseTemplate;
    @Mock private WalletAddressHelper walletAddressHelper;
    @Mock private SigningApprovalExecutionService signingApprovalExecutionService;

    private GatewayConfig gatewayConfig;
    private PaymentServiceImpl paymentService;
    private PaymentOrder order;

    @BeforeEach
    void setUp() {
        gatewayConfig = new GatewayConfig();
        gatewayConfig.getExchangeWallet().setPlatformPubkey("platform-pk");

        paymentService = new PaymentServiceImpl(orderRepository, refundRepository, gatewayConfig,
                chainRpcClient, signingServiceClient, walletMgmtClient, eventPublisher, keyManager,
                riskService, complianceService, (Tracer) null, threePhaseTemplate,
                walletAddressHelper, null, null);
        paymentService.setSigningApprovalExecutionService(signingApprovalExecutionService);

        order = new PaymentOrder();
        order.setId(1L);
        order.setOrderNo("NEX-ORDER-1");
        order.setMerchantId(100L);
        order.setAmount(new BigDecimal("1000000"));
        order.setPayerAddress("payer-addr");
        order.setPayeeAddress("payee-addr");
        order.setTokenSymbol("NEX");
        order.setStatus(PaymentOrder.OrderStatus.PAID);

        lenient().when(riskService.evaluateRefund(any())).thenReturn(RiskDecision.APPROVED);
        lenient().when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        lenient().when(refundRepository.sumActiveRefundsByOrderId(1L)).thenReturn(BigDecimal.ZERO);
        lenient().when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(refundRepository.save(any())).thenAnswer(inv -> {
            Refund refund = inv.getArgument(0);
            refund.setId(10L);
            return refund;
        });
        lenient().when(walletAddressHelper.addressToPubkeyHash("payer-addr")).thenReturn("payer-hash");
        lenient().when(threePhaseTemplate.execute(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    ExecutionRequest request = inv.getArgument(0);
                    Function<ExecutionRequest, Refund> persist = inv.getArgument(1);
                    Function<Refund, OnChainResult> execute = inv.getArgument(2);
                    BiConsumer<Refund, OnChainResult> confirm = inv.getArgument(3);
                    Refund refund = persist.apply(request);
                    OnChainResult result = execute.apply(refund);
                    confirm.accept(refund, result);
                    return refund;
                });
    }

    @Test
    @DisplayName("2001 待审批 -> 退款 AWAITING_APPROVAL，订单保持 REFUND_PENDING，不判失败")
    void refund_awaitingApproval() {
        when(signingApprovalExecutionService.signOrAwait(any(), eq("REFUND"), eq("platform-pk"),
                eq("payer-hash"), any(), eq("NEX")))
                .thenReturn(new SigningOutcome(ApprovalExecutionLink.Status.AWAITING_APPROVAL,
                        "ap-1", null, null));

        Refund result = paymentService.refund(1L, new BigDecimal("50000"), "large refund");

        assertEquals(Refund.RefundStatus.AWAITING_APPROVAL, result.getStatus());
        assertNull(result.getChainTxHash());
        assertEquals(PaymentOrder.OrderStatus.REFUND_PENDING, order.getStatus());
        verify(eventPublisher, never()).publishEvent(any(RefundCompletedEvent.class));
    }

    @Test
    @DisplayName("已执行但无哈希 -> RECONCILIATION_NEEDED，订单保持 REFUND_PENDING")
    void refund_executedUnverified() {
        when(signingApprovalExecutionService.signOrAwait(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED_UNVERIFIED,
                        "ap-2", null, "no txHash"));

        Refund result = paymentService.refund(1L, new BigDecimal("50000"), "large refund");

        assertEquals(Refund.RefundStatus.RECONCILIATION_NEEDED, result.getStatus());
        assertEquals(PaymentOrder.OrderStatus.REFUND_PENDING, order.getStatus());
        verify(eventPublisher, never()).publishEvent(any(RefundCompletedEvent.class));
    }

    @Test
    @DisplayName("签名成功 -> COMPLETED + 订单 REFUNDED + 完成事件")
    void refund_executed() {
        when(signingApprovalExecutionService.signOrAwait(any(), any(), any(), any(), any(), any()))
                .thenReturn(new SigningOutcome(ApprovalExecutionLink.Status.EXECUTED,
                        null, "TX-OK-1", null));

        Refund result = paymentService.refund(1L, new BigDecimal("50000"), "small refund");

        assertEquals(Refund.RefundStatus.COMPLETED, result.getStatus());
        assertEquals("TX-OK-1", result.getChainTxHash());
        assertEquals(PaymentOrder.OrderStatus.REFUNDED, order.getStatus());
        verify(eventPublisher).publishEvent(any(RefundCompletedEvent.class));
    }
}
