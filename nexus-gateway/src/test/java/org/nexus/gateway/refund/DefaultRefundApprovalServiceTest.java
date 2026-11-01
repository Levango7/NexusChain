package org.nexus.gateway.refund;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
/**
 * {@link DefaultRefundApprovalService} 与 {@link DefaultRefundPolicy} 单元测试：
 * 验证退款请求校验、审批/拒绝状态流转与执行。
 */
@ExtendWith(MockitoExtension.class)
class DefaultRefundApprovalServiceTest {

    @Mock private RefundRequestRepository refundRequestRepository;
    @Mock private PaymentOrderRepository paymentOrderRepository;
    @Mock private OnChainExecutionChannel executionChannel;
    @Mock private PlatformTransactionManager transactionManager;

    private DefaultRefundPolicy policy;
    private DefaultRefundApprovalService service;

    private PaymentOrder paidOrder;

    @BeforeEach
    void setUp() throws Exception {
        policy = new DefaultRefundPolicy();
        setField(policy, "refundWindowDays", 7L);
        // 三段式执行以编程式事务独立提交认领/落库段；单测里让事务边界"透传"（回调同步执行）
        lenient().when(transactionManager.getTransaction(any()))
                .thenAnswer(inv -> new SimpleTransactionStatus());
        // 模拟"已正确配置平台退款地址的部署"（2026-11-02 起 @Value 默认为空，
        // 未配置 ⇒ 执行前 fail-closed，见 unsetPlatformRefundAddressFailsClosed）
        service = new DefaultRefundApprovalService(refundRequestRepository, paymentOrderRepository,
                policy, executionChannel, "1PlatformRefundHot0000000000000000000", transactionManager);

        paidOrder = new PaymentOrder();
        paidOrder.setId(1L);
        paidOrder.setOrderNo("NEX-ORDER-001");
        paidOrder.setMerchantId(100L);
        paidOrder.setAmount(new BigDecimal("1000000"));
        paidOrder.setStatus(PaymentOrder.OrderStatus.PAID);
        paidOrder.setPaidAt(LocalDateTime.now().minusDays(1));
        // P0-2 修复后 executeOnChain 会校验付款人地址非空
        paidOrder.setPayerAddress("1PayerAddr00000000000000000000000000000");
    }

    // --- DefaultRefundPolicy ---

    @Test
    void policy_canRefund_paidOrder() {
        assertTrue(policy.canRefund(paidOrder));
    }

    @Test
    void policy_canRefund_pendingOrderFalse() {
        PaymentOrder pending = new PaymentOrder();
        pending.setStatus(PaymentOrder.OrderStatus.PENDING);
        assertFalse(policy.canRefund(pending));
    }

    @Test
    void policy_maxRefundAmount_equalsOrderAmount() {
        assertEquals(0, new BigDecimal("1000000").compareTo(policy.getMaxRefundAmount(paidOrder)));
    }

    @Test
    void policy_refundWindow_withinWindow() {
        assertFalse(policy.getRefundWindow(paidOrder).isZero());
    }

    @Test
    void policy_refundWindow_expired() {
        paidOrder.setPaidAt(LocalDateTime.now().minusDays(30));
        assertTrue(policy.getRefundWindow(paidOrder).isZero());
    }

    // --- requestRefund ---

    @Test
    void requestRefund_validCreatesPending() {
        when(paymentOrderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(paidOrder));
        when(refundRequestRepository.sumPendingRefundsByOrderId(1L)).thenReturn(BigDecimal.ZERO);
        when(refundRequestRepository.save(any())).thenAnswer(inv -> {
            RefundRequest r = inv.getArgument(0);
            r.setId(10L);
            return r;
        });

        RefundRequest result = service.requestRefund(1L, new BigDecimal("500"), "damaged");

        assertEquals(RefundRequest.RefundStatus.PENDING, result.getStatus());
        assertEquals(1L, result.getOrderId());
        assertEquals(100L, result.getMerchantId());
        assertNotNull(result.getRefundNo());
    }

    @Test
    void requestRefund_amountExceedsMaxThrows() {
        when(paymentOrderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(paidOrder));
        assertThrows(IllegalArgumentException.class,
                () -> service.requestRefund(1L, new BigDecimal("2000000"), "too much"));
    }

    @Test
    void requestRefund_ineligibleOrderThrows() {
        PaymentOrder pending = new PaymentOrder();
        pending.setId(2L);
        pending.setStatus(PaymentOrder.OrderStatus.PENDING);
        when(paymentOrderRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(pending));

        assertThrows(IllegalStateException.class,
                () -> service.requestRefund(2L, new BigDecimal("100"), "x"));
    }

    @Test
    void requestRefund_windowExpiredThrows() {
        paidOrder.setPaidAt(LocalDateTime.now().minusDays(30));
        when(paymentOrderRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(paidOrder));
        when(refundRequestRepository.sumPendingRefundsByOrderId(1L)).thenReturn(BigDecimal.ZERO);

        assertThrows(IllegalStateException.class,
                () -> service.requestRefund(1L, new BigDecimal("100"), "late"));
    }

    // --- approveRefund / rejectRefund ---

    @Test
    void approveRefund_pendingBecomesApproved() {
        RefundRequest pending = newPendingRequest();
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(pending));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefundRequest result = service.approveRefund(10L, "approver-1");

        assertEquals(RefundRequest.RefundStatus.APPROVED, result.getStatus());
        assertEquals("approver-1", result.getApproverId());
        assertNotNull(result.getApprovedAt());
    }

    @Test
    void approveRefund_nonPendingThrows() {
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));

        assertThrows(IllegalStateException.class, () -> service.approveRefund(10L, "approver-1"));
    }

    @Test
    void rejectRefund_pendingBecomesRejected() {
        RefundRequest pending = newPendingRequest();
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(pending));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RefundRequest result = service.rejectRefund(10L, "approver-1", "fraud");

        assertEquals(RefundRequest.RefundStatus.REJECTED, result.getStatus());
        assertEquals("fraud", result.getRejectionReason());
    }

    // --- executeRefund（三段式：认领先提交 → 链上事务外 → 结果独立落库） ---

    @Test
    void executeRefund_onChainSuccessMarksExecutedAndOrderRefunded() {
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.claimForExecution(eq(10L), any())).thenReturn(1);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));
        when(refundRequestRepository.markExecuted(eq(10L), eq("TX-ABC-123"), any()))
                .thenAnswer(inv -> {
                    approved.setStatus(RefundRequest.RefundStatus.EXECUTED);
                    approved.setChainTxHash(inv.getArgument(1));
                    approved.setExecutedAt(LocalDateTime.now());
                    return 1;
                });
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(paidOrder));
        when(executionChannel.execute(any(TransactionRequest.class)))
                .thenReturn(TransactionResult.success("TX-ABC-123", 12, false));

        RefundRequest result = service.executeRefund(10L);

        // 链上退款执行成功：请求置 EXECUTED 并记录真实交易哈希；订单迁移至 REFUNDED 终态
        assertEquals(RefundRequest.RefundStatus.EXECUTED, result.getStatus());
        assertEquals("TX-ABC-123", result.getChainTxHash());
        assertNotNull(result.getExecutedAt());
        assertEquals(PaymentOrder.OrderStatus.REFUNDED, paidOrder.getStatus());
    }

    @Test
    void executeRefund_claimCommittedBeforeOnChainCall() {
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        // 记录关键步骤的实际发生顺序（认领 → 认领段 commit → 链上调用）。
        // 不用 InOrder：多次 commit 与 atLeastOnce 的组合语义会吞掉后续事件，事件序列更确定。
        List<String> sequence = new ArrayList<>();
        when(refundRequestRepository.claimForExecution(eq(10L), any())).thenAnswer(inv -> {
            sequence.add("claim");
            return 1;
        });
        doAnswer(inv -> {
            sequence.add("commit");
            return null;
        }).when(transactionManager).commit(any());
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));
        when(refundRequestRepository.markExecuted(eq(10L), any(), any())).thenReturn(1);
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(paidOrder));
        when(executionChannel.execute(any(TransactionRequest.class))).thenAnswer(inv -> {
            sequence.add("execute");
            return TransactionResult.success("TX-ABC-123", 12, false);
        });

        service.executeRefund(10L);

        // 批次⑤ P0：认领（CAS）与认领段 commit 必须都先于不可逆的链上调用——
        // "出款成功后的 commit 失败不会回滚认领"这一防线在时序上的体现
        int claimIdx = sequence.indexOf("claim");
        int commitIdx = sequence.indexOf("commit");
        int executeIdx = sequence.indexOf("execute");
        assertTrue(claimIdx >= 0, "认领未发生: " + sequence);
        assertTrue(commitIdx > claimIdx, "认领段未先提交（commit 应在认领之后）: " + sequence);
        assertTrue(executeIdx > commitIdx, "链上调用未发生在认领段提交之后: " + sequence);
    }

    @Test
    void executeRefund_claimConflictAbortsBeforeOnChainCall() {
        // 并发/重复执行：CAS 认领失败（返回 0），必须完全不触碰链上通道
        when(refundRequestRepository.claimForExecution(eq(10L), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.executeRefund(10L));
        verify(executionChannel, never()).execute(any());
        verify(refundRequestRepository, never()).save(any());
        verify(refundRequestRepository, never()).markExecuted(any(), any(), any());
        verify(refundRequestRepository, never()).markFailed(any(), any(), any());
    }

    @Test
    void executeRefund_onChainFailureMarksFailed() {
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.claimForExecution(eq(10L), any())).thenReturn(1);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));
        when(refundRequestRepository.markFailed(eq(10L), any(), any()))
                .thenAnswer(inv -> {
                    approved.setStatus(RefundRequest.RefundStatus.FAILED);
                    approved.setRejectionReason(inv.getArgument(1));
                    return 1;
                });
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(paidOrder));
        when(executionChannel.execute(any(TransactionRequest.class)))
                .thenReturn(TransactionResult.failure("broadcast rejected", false));

        RefundRequest result = service.executeRefund(10L);

        // 链上退款执行失败（确定未出款）：请求置 FAILED 且不产生交易哈希
        assertEquals(RefundRequest.RefundStatus.FAILED, result.getStatus());
        assertNull(result.getChainTxHash());
        assertNotNull(result.getRejectionReason());
        assertTrue(result.getRejectionReason().contains("broadcast rejected"));
    }

    @Test
    void executeRefund_unsetPlatformRefundAddressFailsClosed() {
        // 2026-11-02（板 20261102-0321，与 wallet 2026-10-27 同型）：平台退款地址未配置
        // ⇒ 执行前 fail-closed 拒绝（FAILED + 原因明写 not configured），
        // 绝不把占位符假地址 "PLATFORM_HOT_WALLET" 发给执行通道。
        service = new DefaultRefundApprovalService(refundRequestRepository, paymentOrderRepository,
                policy, executionChannel, "", transactionManager);
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.claimForExecution(eq(10L), any())).thenReturn(1);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));
        when(refundRequestRepository.markFailed(eq(10L), any(), any()))
                .thenAnswer(inv -> {
                    approved.setStatus(RefundRequest.RefundStatus.FAILED);
                    approved.setRejectionReason(inv.getArgument(1));
                    return 1;
                });

        RefundRequest result = service.executeRefund(10L);

        assertEquals(RefundRequest.RefundStatus.FAILED, result.getStatus());
        assertNull(result.getChainTxHash());
        assertNotNull(result.getRejectionReason());
        assertTrue(result.getRejectionReason().contains("not configured"));
        // 执行通道零交互（出款确定未发生），付款人地址也从未被查询（配置校验在前）
        verifyNoInteractions(executionChannel);
        verify(paymentOrderRepository, never()).findById(any());
    }

    @Test
    void executeRefund_broadcastNotConfirmedDoesNotMarkFailed() {
        // P0（2026-10-21）：已广播但未达确认数（PENDING_CONFIRMATION）= 链上结果未知。
        // 必须保持 EXECUTING 交由 RefundExecutionReconciler 告警，绝不能判 FAILED——
        // 否则交易已在链上、记录却是 FAILED，账实不符且无任何路径纠正。
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.claimForExecution(eq(10L), any())).thenReturn(1);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(paidOrder));
        when(executionChannel.execute(any(TransactionRequest.class)))
                .thenReturn(TransactionResult.pending("TX-PENDING-1", 0, false));

        assertThrows(RefundSubmitOutcomeUnknownException.class, () -> service.executeRefund(10L));

        // 不得落 FAILED（假阴性），也不得当作已执行
        verify(refundRequestRepository, never()).markFailed(any(), any(), any());
        verify(refundRequestRepository, never()).markExecuted(any(), any(), any());
        // 订单不得迁移到 REFUNDED（结果未知）
        assertEquals(PaymentOrder.OrderStatus.PAID, paidOrder.getStatus());
    }

    @Test
    void executeRefund_awaitingApprovalMarksAwaitingNotFailed() {
        // 大额退款：signing-service 返回 2001（未签名/未广播）。
        // 单据必须进 AWAITING_APPROVAL（非终态），绝不能判 FAILED ——
        // 旧实现把"取不到 txHash"当失败，审批通过后也没有任何路径能重试。
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.claimForExecution(eq(10L), any())).thenReturn(1);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));
        when(refundRequestRepository.markAwaitingApproval(eq(10L), any(), any()))
                .thenAnswer(inv -> {
                    approved.setStatus(RefundRequest.RefundStatus.AWAITING_APPROVAL);
                    approved.setRejectionReason(inv.getArgument(1));
                    return 1;
                });
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(paidOrder));
        when(executionChannel.execute(any(TransactionRequest.class)))
                .thenReturn(TransactionResult.awaitingApproval("AP-1"));

        RefundRequest result = service.executeRefund(10L);

        assertEquals(RefundRequest.RefundStatus.AWAITING_APPROVAL, result.getStatus());
        assertNull(result.getChainTxHash());
        verify(refundRequestRepository, never()).markFailed(any(), any(), any());
        verify(refundRequestRepository, never()).markExecuted(any(), any(), any());
        assertEquals(PaymentOrder.OrderStatus.PAID, paidOrder.getStatus());
    }

    @Test
    void executeRefund_fromAwaitingApprovalReclaimsAndExecutes() {
        // 审批通过后的推进：APPROVED 认领失败（已不在 APPROVED），
        // 由 AWAITING_APPROVAL 认领接住；链上返回成功后正常落 EXECUTED。
        RefundRequest awaiting = newPendingRequest();
        awaiting.setStatus(RefundRequest.RefundStatus.AWAITING_APPROVAL);
        when(refundRequestRepository.claimForExecution(eq(10L), any())).thenReturn(0);
        when(refundRequestRepository.claimAwaitingForExecution(eq(10L), any())).thenReturn(1);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(awaiting));
        when(refundRequestRepository.markExecuted(eq(10L), eq("TX-OK-1"), any()))
                .thenAnswer(inv -> {
                    awaiting.setStatus(RefundRequest.RefundStatus.EXECUTED);
                    awaiting.setChainTxHash(inv.getArgument(1));
                    return 1;
                });
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(paidOrder));
        when(executionChannel.execute(any(TransactionRequest.class)))
                .thenReturn(TransactionResult.success("TX-OK-1", 12, false));

        RefundRequest result = service.executeRefund(10L);

        assertEquals(RefundRequest.RefundStatus.EXECUTED, result.getStatus());
        assertEquals("TX-OK-1", result.getChainTxHash());
        assertEquals(PaymentOrder.OrderStatus.REFUNDED, paidOrder.getStatus());
        verify(refundRequestRepository).claimAwaitingForExecution(eq(10L), any());
    }

    @Test
    void executeRefund_executedUnverifiedKeepsExecutingNotFailed() {
        // 已执行但调用方无哈希：资金可能已出，必须保持 EXECUTING 交人工核对，
        // 绝不能落 FAILED（会诱导重退造成二次打款）。
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.claimForExecution(eq(10L), any())).thenReturn(1);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(paidOrder));
        when(executionChannel.execute(any(TransactionRequest.class)))
                .thenReturn(TransactionResult.executedUnverified("AP-2", "no txHash"));

        assertThrows(RefundSubmitOutcomeUnknownException.class, () -> service.executeRefund(10L));

        verify(refundRequestRepository, never()).markFailed(any(), any(), any());
        verify(refundRequestRepository, never()).markExecuted(any(), any(), any());
        assertEquals(PaymentOrder.OrderStatus.PAID, paidOrder.getStatus());
    }

    @Test
    void executeRefund_finalizeFailureRethrowsAndNeverMarksFailed() {
        // 链上已出款但结果落库失败：单据停留 EXECUTING（结果未知）——
        // 必须原样抛出交由 RefundExecutionReconciler 告警，不得改 FAILED（假阴性）、不得重试出款
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.claimForExecution(eq(10L), any())).thenReturn(1);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));
        when(refundRequestRepository.markExecuted(eq(10L), any(), any())).thenReturn(0);
        when(paymentOrderRepository.findById(1L)).thenReturn(Optional.of(paidOrder));
        when(executionChannel.execute(any(TransactionRequest.class)))
                .thenReturn(TransactionResult.success("TX-ABC-123", 12, false));

        assertThrows(IllegalStateException.class, () -> service.executeRefund(10L));

        verify(executionChannel, times(1)).execute(any(TransactionRequest.class));
        verify(refundRequestRepository, never()).markFailed(any(), any(), any());
        assertEquals(PaymentOrder.OrderStatus.PAID, paidOrder.getStatus());
    }

    @Test
    void executeRefund_orderTransitionFailureDoesNotFailRefund() {
        // 订单迁移失败不是资金风险（超退防线独立于订单状态，sum 已计入 EXECUTED）：
        // 退款单仍按 EXECUTED 返回，订单停留原状态待人工追查
        RefundRequest approved = newPendingRequest();
        approved.setStatus(RefundRequest.RefundStatus.APPROVED);
        when(refundRequestRepository.claimForExecution(eq(10L), any())).thenReturn(1);
        when(refundRequestRepository.findById(10L)).thenReturn(Optional.of(approved));
        when(refundRequestRepository.markExecuted(eq(10L), eq("TX-ABC-123"), any()))
                .thenAnswer(inv -> {
                    approved.setStatus(RefundRequest.RefundStatus.EXECUTED);
                    approved.setChainTxHash(inv.getArgument(1));
                    approved.setExecutedAt(LocalDateTime.now());
                    return 1;
                });
        // 第一次 findById 供 executeOnChain 校验付款人地址；第二次（订单迁移）返回空 → 迁移失败
        when(paymentOrderRepository.findById(1L))
                .thenReturn(Optional.of(paidOrder), Optional.empty());
        when(executionChannel.execute(any(TransactionRequest.class)))
                .thenReturn(TransactionResult.success("TX-ABC-123", 12, false));

        RefundRequest result = service.executeRefund(10L);

        assertEquals(RefundRequest.RefundStatus.EXECUTED, result.getStatus());
        assertEquals(PaymentOrder.OrderStatus.PAID, paidOrder.getStatus());
    }

    private RefundRequest newPendingRequest() {
        RefundRequest r = new RefundRequest();
        r.setId(10L);
        r.setRefundNo("RF123");
        r.setOrderId(1L);
        r.setMerchantId(100L);
        r.setAmount(new BigDecimal("500"));
        r.setStatus(RefundRequest.RefundStatus.PENDING);
        return r;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
