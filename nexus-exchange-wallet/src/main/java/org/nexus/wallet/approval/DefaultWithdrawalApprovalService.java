package org.nexus.wallet.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default withdrawal approval service implementation.
 *
 * <p>Drives the multi-approver withdrawal workflow in memory:</p>
 * <ul>
 *   <li>{@link #requestWithdrawal}：校验白名单与金额 → 按 {@link ApprovalPolicy}
 *       确定所需审批人数 → 请求置 PENDING</li>
 *   <li>{@link #approve}：累计审批（防重复审批人）→ 达到阈值置 APPROVED</li>
 *   <li>{@link #reject}：置 REJECTED 并记录原因</li>
 *   <li>{@link #executeApprovedWithdrawal}：校验 APPROVED 状态 → 生成模拟链上交易
 *       哈希置 EXECUTED（链上广播接入后替换此步）</li>
 * </ul>
 *
 * <p>请求存储为进程内内存表；生产环境需替换为持久化存储。链上执行当前为
 * 模拟交易哈希（标记 SIMULATED），接入钱包签名管道后替换。</p>
 */
@Service
public class DefaultWithdrawalApprovalService implements WithdrawalApprovalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultWithdrawalApprovalService.class);

    private final ApprovalPolicy approvalPolicy;

    /** Withdrawal request store: requestId → request. */
    private final Map<String, WithdrawalRequest> requests = new ConcurrentHashMap<String, WithdrawalRequest>();

    public DefaultWithdrawalApprovalService(ApprovalPolicy approvalPolicy) {
        this.approvalPolicy = approvalPolicy;
    }

    @Override
    public WithdrawalRequest requestWithdrawal(String to, BigDecimal amount, String currency) {
        if (to == null || to.isEmpty()) {
            throw new IllegalArgumentException("to address is required");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (currency == null || currency.isEmpty()) {
            throw new IllegalArgumentException("currency is required");
        }
        if (!approvalPolicy.isAddressWhitelisted(to)) {
            throw new IllegalStateException("address not whitelisted: " + to);
        }

        WithdrawalRequest request = new WithdrawalRequest();
        request.setRequestId("WD-" + UUID.randomUUID().toString().replace("-", ""));
        request.setToAddress(to);
        request.setAmount(amount);
        request.setCurrency(currency);
        request.setStatus(WithdrawalRequest.WithdrawalStatus.PENDING);
        request.setRequiredApprovers(approvalPolicy.getRequiredApprovers(amount, currency));
        request.setApprovedCount(0);
        request.setCreatedAt(LocalDateTime.now());

        requests.put(request.getRequestId(), request);
        log.info("Withdrawal requested: requestId={}, to={}, amount={}, requiredApprovers={}",
                request.getRequestId(), to, amount, request.getRequiredApprovers());
        return request;
    }

    @Override
    public WithdrawalRequest approve(String approvalId, String approverId) {
        if (approvalId == null || approverId == null) {
            throw new IllegalArgumentException("approvalId and approverId are required");
        }
        WithdrawalRequest request = requests.get(approvalId);
        if (request == null) {
            throw new IllegalArgumentException("withdrawal request not found: " + approvalId);
        }
        if (request.getStatus() != WithdrawalRequest.WithdrawalStatus.PENDING) {
            throw new IllegalStateException("request is not pending: status=" + request.getStatus());
        }
        if (request.getApprovers().contains(approverId)) {
            throw new IllegalStateException("approver already approved: " + approverId);
        }

        request.getApprovers().add(approverId);
        request.setApprovedCount(request.getApprovers().size());

        if (request.getApprovedCount() >= request.getRequiredApprovers()) {
            request.setStatus(WithdrawalRequest.WithdrawalStatus.APPROVED);
            log.info("Withdrawal approved: requestId={}, approvers={}",
                    approvalId, request.getApprovedCount());
        } else {
            log.info("Withdrawal approval recorded: requestId={}, approver={}, count={}/{}",
                    approvalId, approverId, request.getApprovedCount(), request.getRequiredApprovers());
        }
        return request;
    }

    @Override
    public WithdrawalRequest reject(String approvalId, String approverId, String reason) {
        if (approvalId == null || approverId == null) {
            throw new IllegalArgumentException("approvalId and approverId are required");
        }
        WithdrawalRequest request = requests.get(approvalId);
        if (request == null) {
            throw new IllegalArgumentException("withdrawal request not found: " + approvalId);
        }
        if (request.getStatus() != WithdrawalRequest.WithdrawalStatus.PENDING) {
            throw new IllegalStateException("request is not pending: status=" + request.getStatus());
        }

        request.setStatus(WithdrawalRequest.WithdrawalStatus.REJECTED);
        request.setRejectionReason(reason == null ? "rejected by " + approverId : reason);
        log.info("Withdrawal rejected: requestId={}, by={}, reason={}",
                approvalId, approverId, request.getRejectionReason());
        return request;
    }

    @Override
    public WithdrawalRequest executeApprovedWithdrawal(String approvalId) {
        if (approvalId == null) {
            throw new IllegalArgumentException("approvalId is required");
        }
        WithdrawalRequest request = requests.get(approvalId);
        if (request == null) {
            throw new IllegalArgumentException("withdrawal request not found: " + approvalId);
        }
        if (request.getStatus() != WithdrawalRequest.WithdrawalStatus.APPROVED) {
            throw new IllegalStateException("request is not approved: status=" + request.getStatus());
        }

        try {
            // TODO: construct on-chain withdrawal tx, sign via MPC pipeline, broadcast.
            // Simulated tx hash until the wallet signing pipeline is wired.
            String txHash = "SIMULATED-" + UUID.randomUUID().toString().replace("-", "");
            request.setChainTxHash(txHash);
            request.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTED);
            request.setExecutedAt(LocalDateTime.now());
            log.info("Withdrawal executed: requestId={}, txHash={}", approvalId, txHash);
        } catch (Exception e) {
            request.setStatus(WithdrawalRequest.WithdrawalStatus.FAILED);
            request.setRejectionReason("execution failed: " + e.getMessage());
            log.error("Withdrawal execution failed: requestId={}", approvalId, e);
        }
        return request;
    }

    /**
     * Query a withdrawal request by ID.
     *
     * @param requestId request ID
     * @return the request, or null if not found
     */
    public WithdrawalRequest getRequest(String requestId) {
        return requestId == null ? null : requests.get(requestId);
    }
}
