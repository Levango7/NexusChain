package org.nexus.gateway.refund;

import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>链上退款执行当前为模拟交易哈希（SIMULATED 前缀），接入
 * nexus-exchange-wallet 签名管道后替换 {@link #executeOnChain}。</p>
 */
@Service
public class DefaultRefundApprovalService implements RefundApprovalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRefundApprovalService.class);

    private final RefundRequestRepository refundRequestRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final RefundPolicy refundPolicy;

    public DefaultRefundApprovalService(RefundRequestRepository refundRequestRepository,
                                        PaymentOrderRepository paymentOrderRepository,
                                        RefundPolicy refundPolicy) {
        this.refundRequestRepository = refundRequestRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.refundPolicy = refundPolicy;
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

        PaymentOrder order = paymentOrderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("order not found: " + orderId));

        if (!refundPolicy.canRefund(order)) {
            throw new IllegalStateException("order is not eligible for refund: status=" + order.getStatus());
        }
        BigDecimal maxRefund = refundPolicy.getMaxRefundAmount(order);
        if (amount.compareTo(maxRefund) > 0) {
            throw new IllegalArgumentException(
                    "refund amount exceeds maximum: " + amount + " > " + maxRefund);
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
        request.setApproverId(approverId);
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
            log.info("Refund executed: refundId={}, txHash={}", refundId, txHash);
        } catch (Exception e) {
            request.setStatus(RefundRequest.RefundStatus.FAILED);
            request.setRejectionReason("execution failed: " + e.getMessage());
            log.error("Refund execution failed: refundId={}", refundId, e);
        }
        return refundRequestRepository.save(request);
    }

    /**
     * Trigger the on-chain refund transfer.
     *
     * <p>TODO: wire to the nexus-exchange-wallet signing pipeline and broadcast
     * the refund transaction. Currently returns a simulated tx hash.</p>
     */
    private String executeOnChain(RefundRequest request) {
        return "SIMULATED-" + UUID.randomUUID().toString().replace("-", "");
    }
}
