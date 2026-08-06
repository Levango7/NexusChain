package org.nexus.walletsvc.approval;

import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.signing.ApprovalPolicy;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li>{@link #executeApprovedWithdrawal}：校验 APPROVED 状态 → 通过
 *       {@link SigningServiceFeignClient} 调用 signing-service 的
 *       {@code /api/v1/transfers/sign} 端点完成提现签名广播
 *       → 成功置 EXECUTED 带交易哈希，失败置 FAILED</li>
 * </ul>
 *
 * <p>请求存储为进程内内存表；生产环境需替换为持久化存储。</p>
 *
 * <p>跨服务调用：wallet-service 通过 Feign 调用 signing-service（设计文档 §3.2 方案 A），
 * 符合「wallet 管理审批、signing 负责签名」边界。原 exchange-wallet 中通过
 * {@code OnChainExecutionClient} 调 gateway 的链上执行通道已删除，改为直接调
 * signing-service 完成签名 + 广播。</p>
 *
 * <p>迁移历史：原位于 {@code org.nexus.wallet.wallet.approval.DefaultWithdrawalApprovalService}
 * （nexus-exchange-wallet），在 Phase 2 微服务化中迁移至 nexus-wallet-service
 * （新包 {@code org.nexus.walletsvc.approval}）。Phase 2 任务 #57 改造：
 * 删除对 {@code OnChainExecutionClient} 的依赖，改为注入 {@link SigningServiceFeignClient}。</p>
 */
@Service
public class DefaultWithdrawalApprovalService implements WithdrawalApprovalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultWithdrawalApprovalService.class);

    /** 平台热钱包地址默认值 */
    private static final String DEFAULT_PLATFORM_WALLET_ADDRESS = "PLATFORM_HOT_WALLET";

    private final ApprovalPolicy approvalPolicy;
    private final SigningServiceFeignClient signingServiceClient;
    private final String platformWalletAddress;

    /** Withdrawal request store: requestId → request. */
    private final Map<String, WithdrawalRequest> requests = new ConcurrentHashMap<String, WithdrawalRequest>();

    public DefaultWithdrawalApprovalService(ApprovalPolicy approvalPolicy) {
        this(approvalPolicy, null, DEFAULT_PLATFORM_WALLET_ADDRESS);
    }

    @Autowired
    public DefaultWithdrawalApprovalService(ApprovalPolicy approvalPolicy,
                                             SigningServiceFeignClient signingServiceClient,
                                             @Value("${nexus.wallet.platform-address:PLATFORM_HOT_WALLET}") String platformWalletAddress) {
        this.approvalPolicy = approvalPolicy;
        this.signingServiceClient = signingServiceClient;
        this.platformWalletAddress = platformWalletAddress != null && !platformWalletAddress.isEmpty()
                ? platformWalletAddress : DEFAULT_PLATFORM_WALLET_ADDRESS;
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
            String txHash;
            if (signingServiceClient != null) {
                // 通过 Feign 调用 signing-service 的 /api/v1/transfers/sign 端点
                // signing-service 使用平台密钥库完成签名 + 广播，返回交易哈希
                String result = signingServiceClient.signTransfer(
                        platformWalletAddress,
                        request.getToAddress(),
                        request.getAmount());
                if (result == null || result.isEmpty()) {
                    request.setStatus(WithdrawalRequest.WithdrawalStatus.FAILED);
                    request.setRejectionReason("signing service returned empty result");
                    log.error("Withdrawal execution failed: requestId={}, signing service returned empty", approvalId);
                    return request;
                }
                txHash = result;
                log.info("Withdrawal executed via signing-service: requestId={}, txHash={}",
                        approvalId, txHash);
            } else {
                // fallback：签名服务客户端未注入，使用模拟 txHash（向后兼容，测试 / 独立运行场景）
                txHash = "SIMULATED-" + UUID.randomUUID().toString().replace("-", "");
                log.warn("Withdrawal executed with fallback SIMULATED tx (no signing service client): requestId={}", approvalId);
            }
            request.setChainTxHash(txHash);
            request.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTED);
            request.setExecutedAt(LocalDateTime.now());
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
