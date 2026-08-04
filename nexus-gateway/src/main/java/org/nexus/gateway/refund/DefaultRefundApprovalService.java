package org.nexus.gateway.refund;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Default skeleton implementation of {@link RefundApprovalService}.
 *
 * <p>All methods are stubbed and log a TODO marker. Production wiring should
 * enforce the {@link RefundPolicy} on request, persist the refund request,
 * notify approvers, and execute the on-chain refund via the
 * {@code nexus-exchange-wallet} signing pipeline.</p>
 */
@Service
public class DefaultRefundApprovalService implements RefundApprovalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultRefundApprovalService.class);

    @Override
    public RefundRequest requestRefund(Long orderId, BigDecimal amount, String reason) {
        // TODO: load the PaymentOrder and verify RefundPolicy.canRefund(order)
        // TODO: verify amount <= RefundPolicy.getMaxRefundAmount(order)
        // TODO: verify RefundPolicy.getRefundWindow(order) > 0
        // TODO: persist a new RefundRequest in PENDING status and notify approvers
        log.warn("requestRefund not implemented: orderId={}, amount={}, reason={}", orderId, amount, reason);
        RefundRequest stub = new RefundRequest();
        stub.setOrderId(orderId);
        stub.setAmount(amount);
        stub.setReason(reason);
        return stub;
    }

    @Override
    public RefundRequest approveRefund(Long refundId, String approverId) {
        // TODO: load RefundRequest, verify status = PENDING
        // TODO: set status = APPROVED, approverId, approvedAt = now; persist
        log.warn("approveRefund not implemented: refundId={}, approverId={}", refundId, approverId);
        return null;
    }

    @Override
    public RefundRequest rejectRefund(Long refundId, String approverId, String reason) {
        // TODO: load RefundRequest, verify status = PENDING
        // TODO: set status = REJECTED, approverId, rejectionReason; persist
        log.warn("rejectRefund not implemented: refundId={}, approverId={}, reason={}",
                refundId, approverId, reason);
        return null;
    }

    @Override
    public RefundRequest executeRefund(Long refundId) {
        // TODO: load RefundRequest, verify status = APPROVED
        // TODO: trigger on-chain refund via nexus-exchange-wallet signing pipeline
        // TODO: on success set status = EXECUTED with chainTxHash; on failure set status = FAILED
        log.warn("executeRefund not implemented: refundId={}", refundId);
        return null;
    }
}