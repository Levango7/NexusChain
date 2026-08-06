package org.nexus.wallet.wallet.approval;

import org.nexus.sdk.signing.ApprovalPolicy;

import java.math.BigDecimal;

/**
 * Withdrawal approval service interface driving the multi-approver withdrawal
 * workflow.
 *
 * <p>Lifecycle: a withdrawal is {@link #requestWithdrawal requested} in
 * {@link WithdrawalRequest.WithdrawalStatus#PENDING}, then accumulates
 * {@link #approve approvals} until {@code approvedCount >= requiredApprovers},
 * at which point it transitions to {@link WithdrawalRequest.WithdrawalStatus#APPROVED}
 * and can be {@link #executeApprovedWithdrawal executed} on-chain. A request
 * may also be {@link #reject rejected} before reaching the approval threshold.</p>
 */
public interface WithdrawalApprovalService {

    /**
     * Request a withdrawal to the given address. The request enters PENDING
     * status with requiredApprovers determined by the {@link ApprovalPolicy}.
     *
     * @param to       target wallet address
     * @param amount   withdrawal amount
     * @param currency currency symbol
     * @return the created withdrawal request
     */
    WithdrawalRequest requestWithdrawal(String to, BigDecimal amount, String currency);

    /**
     * Record an approval from the given approver. When the approval threshold
     * is reached, the request transitions to APPROVED status.
     *
     * @param approvalId withdrawal request ID
     * @param approverId approver identifier
     * @return the updated withdrawal request
     */
    WithdrawalRequest approve(String approvalId, String approverId);

    /**
     * Reject the withdrawal request.
     *
     * @param approvalId withdrawal request ID
     * @param approverId approver identifier
     * @param reason     rejection reason
     * @return the updated withdrawal request in REJECTED status
     */
    WithdrawalRequest reject(String approvalId, String approverId, String reason);

    /**
     * Execute an approved withdrawal on-chain.
     *
     * @param approvalId withdrawal request ID
     * @return the updated withdrawal request in EXECUTED (or FAILED) status
     */
    WithdrawalRequest executeApprovedWithdrawal(String approvalId);
}