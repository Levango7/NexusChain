package org.nexus.gateway.refund;

import org.nexus.gateway.model.OrderStateMachine;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Default refund approval service implementation.
 *
 * <p>Drives the refund approval workflow backed by {@link RefundRequestRepository}:</p>
 * <ul>
 *   <li>{@link #requestRefund}：校验订单可退（{@link RefundPolicy}）→ 金额不超上限
 *       → 退款窗口未过期 → 落库 PENDING</li>
 *   <li>{@link #approveRefund}：校验 PENDING → 置 APPROVED 并记录审批人与时间</li>
 *   <li>{@link #rejectRefund}：校验 PENDING → 置 REJECTED 并记录拒绝原因</li>
 *   <li>{@link #executeRefund}：校验 APPROVED → 触发链上退款 → 成功置 EXECUTED
 *       带交易哈希，失败置 FAILED</li>
 * </ul>
 *
 * <p><b>链上退款执行已接入：</b>{@link #executeOnChain} 通过
 * {@link OnChainExecutionChannel} 发起链上退款转账，返回真实交易哈希
 * （sandbox 模式下返回 "SIMULATED-..." 前缀的模拟哈希）。</p>
 */
@Service
public class DefaultRefundApprovalService implements RefundApprovalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRefundApprovalService.class);

    /** 平台退款热钱包地址默认值 */
    private static final String DEFAULT_PLATFORM_REFUND_ADDRESS = "PLATFORM_HOT_WALLET";

    private final RefundRequestRepository refundRequestRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final RefundPolicy refundPolicy;
    private final OnChainExecutionChannel executionChannel;
    private final String platformRefundAddress;

    public DefaultRefundApprovalService(RefundRequestRepository refundRequestRepository,
                                        PaymentOrderRepository paymentOrderRepository,
                                        RefundPolicy refundPolicy) {
        this(refundRequestRepository, paymentOrderRepository, refundPolicy, null,
                DEFAULT_PLATFORM_REFUND_ADDRESS);
    }

    @Autowired
    public DefaultRefundApprovalService(RefundRequestRepository refundRequestRepository,
                                        PaymentOrderRepository paymentOrderRepository,
                                        RefundPolicy refundPolicy,
                                        OnChainExecutionChannel executionChannel,
                                        @Value("${nexus.gateway.refund.platform-address:PLATFORM_HOT_WALLET}") String platformRefundAddress) {
        this.refundRequestRepository = refundRequestRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.refundPolicy = refundPolicy;
        this.executionChannel = executionChannel;
        this.platformRefundAddress = platformRefundAddress != null && !platformRefundAddress.isEmpty()
                ? platformRefundAddress : DEFAULT_PLATFORM_REFUND_ADDRESS;
    }

    @Override
    @Transactional
    public RefundRequest requestRefund(Long orderId, BigDecimal amount, String reason) {
        if (orderId == null) {
            throw new IllegalArgumentException("orderId is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }

        // P0-2 修复：使用悲观锁查询订单，防止并发双花
        PaymentOrder order = paymentOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));

        if (!refundPolicy.canRefund(order)) {
            throw new IllegalStateException("order is not eligible for refund: status=" + order.getStatus());
        }
        BigDecimal maxRefund = refundPolicy.getMaxRefundAmount(order);
        if (amount.compareTo(maxRefund) > 0) {
            throw new IllegalArgumentException(
                    "refund amount exceeds maximum: " + amount + " > " + maxRefund);
        }
        // P0-1 修复：检查已有退款总和，防止超额退款
        BigDecimal pendingSum = refundRequestRepository.sumPendingRefundsByOrderId(orderId);
        BigDecimal availableAmount = order.getAmount().subtract(pendingSum);
        if (amount.compareTo(availableAmount) > 0) {
            throw new IllegalArgumentException(
                    "refund exceeds available: requested=" + amount + ", available=" + availableAmount
                            + " (order amount=" + order.getAmount() + ", pending refunds=" + pendingSum + ")");
        }
        if (refundPolicy.getRefundWindow(order).isZero()) {
            throw new IllegalStateException("refund window has expired for order: " + orderId);
        }

        RefundRequest request = new RefundRequest();
        request.setRefundNo("RF" + System.currentTimeMillis()
                + UUID.randomUUID().toString().substring(0, 4).toUpperCase());
        request.setOrderId(orderId);
        request.setMerchantId(order.getMerchantId());
        request.setAmount(amount);
        request.setReason(reason);
        request.setStatus(RefundRequest.RefundStatus.PENDING);

        RefundRequest saved = refundRequestRepository.save(request);
        log.info("Refund requested: refundNo={}, orderId={}, amount={}, reason={}",
                saved.getRefundNo(), orderId, amount, reason);
        return saved;
    }

    @Override
    @Transactional
    public RefundRequest approveRefund(Long refundId, String approverId) {
        if (refundId == null || approverId == null) {
            throw new IllegalArgumentException("refundId and approverId are required");
        }
        RefundRequest request = refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new IllegalArgumentException("refund request not found: " + refundId));
        if (request.getStatus() != RefundRequest.RefundStatus.PENDING) {
            throw new IllegalStateException("refund is not pending: status=" + request.getStatus());
        }

        request.setStatus(RefundRequest.RefundStatus.APPROVED);
        request.setApproverId(approverId);
        request.setApprovedAt(LocalDateTime.now());

        RefundRequest saved = refundRequestRepository.save(request);
        log.info("Refund approved: refundId={}, approver={}", refundId, approverId);
        return saved;
    }

    @Override
    @Transactional
    public RefundRequest rejectRefund(Long refundId, String approverId, String reason) {
        if (refundId == null || approverId == null) {
            throw new IllegalArgumentException("refundId and approverId are required");
        }
        RefundRequest request = refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new IllegalArgumentException("refund request not found: " + refundId));
        if (request.getStatus() != RefundRequest.RefundStatus.PENDING) {
            throw new IllegalStateException("refund is not pending: status=" + request.getStatus());
        }

        request.setStatus(RefundRequest.RefundStatus.REJECTED);
        request.setRejectionReason(reason == null ? "rejected by " + approverId : reason);
        request.setApprovedAt(LocalDateTime.now());

        RefundRequest saved = refundRequestRepository.save(request);
        log.info("Refund rejected: refundId={}, approver={}, reason={}",
                refundId, approverId, saved.getRejectionReason());
        return saved;
    }

    @Override
    @Transactional
    public RefundRequest executeRefund(Long refundId) {
        if (refundId == null) {
            throw new IllegalArgumentException("refundId is required");
        }
        RefundRequest request = refundRequestRepository.findById(refundId)
                .orElseThrow(() -> new IllegalArgumentException("refund request not found: " + refundId));
        if (request.getStatus() != RefundRequest.RefundStatus.APPROVED) {
            throw new IllegalStateException("refund is not approved: status=" + request.getStatus());
        }

        try {
            String txHash = executeOnChain(request);
            request.setChainTxHash(txHash);
            request.setStatus(RefundRequest.RefundStatus.EXECUTED);
            request.setExecutedAt(LocalDateTime.now());

            // P0-1 修复（v2.27.0）：退款执行成功后将订单状态迁移到 REFUNDED，
            // 防止同一订单被无限次重复放款。OrderStateMachine 支持 PAID → REFUNDED 迁移。
            PaymentOrder order = paymentOrderRepository.findById(request.getOrderId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Order not found after refund execution: orderId=" + request.getOrderId()));
            OrderStateMachine.transition(order, PaymentOrder.OrderStatus.REFUNDED);
            paymentOrderRepository.save(order);

            log.info("Refund executed: refundId={}, txHash={}", refundId, txHash);
        } catch (RuntimeException e) {
            request.setStatus(RefundRequest.RefundStatus.FAILED);
            request.setRejectionReason("execution failed: " + e.getMessage());
            log.error("Refund execution failed: refundId={}", refundId, e);
        }
        return refundRequestRepository.save(request);
    }

    /**
     * Trigger the on-chain refund transfer via {@link OnChainExecutionChannel}.
     *
     * <p>构造 {@link TransactionRequest}（type=REFUND）并调用统一链上执行通道。
     * sandbox 模式下返回 "SIMULATED-..." 前缀的模拟交易哈希；
     * 生产模式下返回真实链上交易哈希。</p>
     *
     * @param request the approved refund request to execute
     * @return the on-chain transaction hash (real or simulated)
     * @throws IllegalStateException if the execution channel is not injected or execution fails
     */
    private String executeOnChain(RefundRequest request) {
        if (executionChannel == null) {
            throw new IllegalStateException("OnChainExecutionChannel is not injected");
        }
        // P0-2 修复（v2.27.0）：退款收款方必须是原付款人地址，而非字符串常量 "REFUND:"+refundNo。
        // 原实现将退款转到一个不存在的地址 "REFUND:RF..."，资金永远无法被收款人领取。
        PaymentOrder order = paymentOrderRepository.findById(request.getOrderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Order not found for refund: orderId=" + request.getOrderId()));
        String payerAddress = order.getPayerAddress();
        if (payerAddress == null || payerAddress.isBlank()) {
            throw new IllegalStateException(
                    "Payer address is null for order: " + request.getOrderId()
                            + "; refund cannot be sent to an unknown recipient");
        }
        TransactionRequest txReq = new TransactionRequest(
                TransactionRequest.Type.REFUND,
                platformRefundAddress,
                payerAddress,
                request.getAmount(),
                "NEX",
                "refund:" + request.getRefundNo(),
                "refund:" + request.getId());
        TransactionResult result = executionChannel.execute(txReq);
        if (result == null || !result.isSuccess() || result.getTxHash() == null) {
            String err = result != null ? result.getError() : "execution channel returned null";
            throw new IllegalStateException("on-chain refund execution failed: " + err);
        }
        // P0-2 修复（v2.27.0）：生产环境不应使用模拟交易，记录安全告警。
        if (result.isSimulated()) {
            log.warn("SECURITY: refund executed in simulated mode: refundId={}, txHash={}. "
                    + "MUST not happen in production.",
                    request.getId(), result.getTxHash());
        }
        log.info("refund on-chain executed: refundId={}, txHash={}, simulated={}",
                request.getId(), result.getTxHash(), result.isSimulated());
        return result.getTxHash();
    }
}
