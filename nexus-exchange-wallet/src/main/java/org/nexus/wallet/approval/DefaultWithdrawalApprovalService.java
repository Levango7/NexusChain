package org.nexus.wallet.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Default skeleton implementation of {@link WithdrawalApprovalService}.
 *
 * <p>All methods are stubbed and log a TODO marker. Production wiring should
 * enforce the {@link ApprovalPolicy} on request, persist the withdrawal
 * request, accumulate approvals until the threshold is reached, and execute
 * the on-chain withdrawal via the wallet signing pipeline.</p>
 */
@Service
public class DefaultWithdrawalApprovalService implements WithdrawalApprovalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultWithdrawalApprovalService.class);

    @Override
    public WithdrawalRequest requestWithdrawal(String to, BigDecimal amount, String currency) {
        // TODO: verify ApprovalPolicy.isAddressWhitelisted(to) or apply first-time delay
        // TODO: set requiredApprovers = ApprovalPolicy.getRequiredApprovers(amount, currency)
        // TODO: persist a new WithdrawalRequest in PENDING status and notify approvers
        log.warn("requestWithdrawal not implemented: to={}, amount={}, currency={}", to, amount, currency);
        WithdrawalRequest stub = new WithdrawalRequest();
        stub.setToAddress(to);
        stub.setAmount(amount);
        stub.setCurrency(currency);
        return stub;
    }

    @Override
    public WithdrawalRequest approve(String approvalId, String approverId) {
        // TODO: load WithdrawalRequest, verify status = PENDING
        // TODO: verify approverId not already in approvers; append and increment approvedCount
        // TODO: if approvedCount >= requiredApprovers, transition to APPROVED
        log.warn("approve not implemented: approvalId={}, approverId={}", approvalId, approverId);
        return null;
    }

    @Override
    public WithdrawalRequest reject(String approvalId, String approverId, String reason) {
        // TODO: load WithdrawalRequest, verify status = PENDING
        // TODO: set status = REJECTED, rejectionReason; persist and notify requester
        log.warn("reject not implemented: approvalId={}, approverId={}, reason={}",
                approvalId, approverId, reason);
        return null;
    }

    @Override
    public WithdrawalRequest executeApprovedWithdrawal(String approvalId) {
        // TODO: load WithdrawalRequest, verify status = APPROVED
        // TODO: construct on-chain withdrawal tx, sign via MPC pipeline, broadcast
        // TODO: on success set status = EXECUTED with chainTxHash; on failure set status = FAILED
        log.warn("executeApprovedWithdrawal not implemented: approvalId={}", approvalId);
        return null;
    }
}