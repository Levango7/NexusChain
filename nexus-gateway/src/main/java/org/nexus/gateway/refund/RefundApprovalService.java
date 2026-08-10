package org.nexus.gateway.refund;

import java.math.BigDecimal;

/**
 * Refund approval service interface driving the refund approval workflow.
 *
 * <p>Lifecycle: a refund is {@link #requestRefund requested} in
 * {@link RefundRequest.RefundStatus#PENDING}, then either
 * {@link #approveRefund approved} or {@link #rejectRefund rejected}, and
 * finally {@link #executeRefund executed} on-chain after approval.</p>
 */
public interface RefundApprovalService {

    /**
     * Request a refund for an order. The request enters PENDING status.
     *
     * @param orderId order ID
     * @param amount  refund amount
     * @param reason  optional refund reason
     * @return the created refund request
     */
    RefundRequest requestRefund(Long orderId, BigDecimal amount, String reason);

    /**
     * Approve a pending refund request.
     *
     * @param refundId   refund request ID
     * @param approverId approver identifier
     * @return the updated refund request in APPROVED status
     */
    RefundRequest approveRefund(Long refundId, String approverId);

    /**
     * Reject a pending refund request.
     *
     * @param refundId   refund request ID
     * @param approverId approver identifier
     * @param reason     rejection reason
     * @return the updated refund request in REJECTED status
     */
    RefundRequest rejectRefund(Long refundId, String approverId, String reason);

    /**
     * Execute an approved refund on-chain.
     *
     * @param refundId refund request ID
     * @return the updated refund request in EXECUTED (or FAILED) status
     */
    RefundRequest executeRefund(Long refundId);
}