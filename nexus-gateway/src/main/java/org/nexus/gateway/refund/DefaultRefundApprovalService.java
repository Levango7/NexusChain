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
 * <p><b>链上退款执行尚未接入：</b>{@link #executeOnChain} 在真实链上执行通道
 * （nexus-exchange-wallet 签名与广播管道）可用之前抛出
 * {@link UnsupportedOperationException}，绝不返回模拟交易哈希。调用方
 * {@link #executeRefund} 会将请求置为 FAILED 并记录失败原因。</p>
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
     * <p>链上退款执行尚未接入：真实链上执行需要 nexus-exchange-wallet 的签名与广播
     * 管道，当前未集成，因此本方法明确抛出 {@link UnsupportedOperationException}，
     * 绝不返回模拟/占位交易哈希。接入真实执行通道后，应返回链上交易哈希并补充
     * 异步确认逻辑。</p>
     *
     * @param request the approved refund request to execute
     * @return the real on-chain transaction hash once the execution channel is integrated
     * @throws UnsupportedOperationException 链上退款执行未接入，无法产生真实交易哈希
     */
    private String executeOnChain(RefundRequest request) {
        throw new UnsupportedOperationException(
                "on-chain refund execution is not integrated yet: no real tx hash can be produced");
    }
}
