package org.nexus.walletsvc.approval;

import io.seata.spring.annotation.GlobalTransactional;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.signing.ApprovalPolicy;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.entity.WithdrawalApproverEntity;
import org.nexus.walletsvc.entity.WithdrawalRequestEntity;
import org.nexus.walletsvc.entity.WithdrawalRequestMapper;
import org.nexus.walletsvc.repository.WithdrawalApproverRepository;
import org.nexus.walletsvc.repository.WithdrawalRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default withdrawal approval service implementation.
 *
 * <p>Drives the multi-approver withdrawal workflow backed by database persistence:</p>
 * <ul>
 *   <li>{@link #requestWithdrawal}：校验白名单与金额 → 按 {@link ApprovalPolicy}
 *       确定所需审批人数 → 构造 {@link WithdrawalRequestEntity}（PENDING）→
 *       {@link WithdrawalRequestRepository#save} 持久化 → 返回 DTO</li>
 *   <li>{@link #approve}：{@code findByRequestId} 加载 → 校验 PENDING 状态 →
 *       {@code existsByRequestIdAndApproverId} 防重复审批 → 插入
 *       {@link WithdrawalApproverEntity} → {@code countByRequestId} 更新 approvedCount →
 *       达阈值置 APPROVED → save</li>
 *   <li>{@link #reject}：{@code findByRequestId} → 校验 PENDING → 置 REJECTED +
 *       rejectionReason → save</li>
 *   <li>{@link #executeApprovedWithdrawal}：{@code findByRequestId} → 校验 APPROVED →
 *       通过 {@link SigningServiceFeignClient} 调用 signing-service 的
 *       {@code /api/v1/transfers/sign} 端点完成提现签名广播
 *       → 成功置 EXECUTED 带交易哈希，失败置 FAILED → save</li>
 * </ul>
 *
 * <p><strong>Phase 4 任务 #72 改造</strong>（设计文档 §4.4.3 + §4.5）：</p>
 * <ul>
 *   <li>删除原 {@code ConcurrentHashMap<String, WithdrawalRequest> requests} 内存存储，
 *       改用 {@link WithdrawalRequestRepository} + {@link WithdrawalApproverRepository}
 *       数据库持久化（依赖 Phase 4 任务 #69 已创建的 Entity / Repository / Mapper）</li>
 *   <li>审批人列表不再嵌在 {@link WithdrawalRequest} DTO 内存储，拆为独立的
 *       {@code withdrawal_approvers} 一对多表，由 {@link WithdrawalApproverRepository} 管理</li>
 *   <li>防重复审批由 {@link WithdrawalApproverRepository#existsByRequestIdAndApproverId}
 *       检查（数据库唯一约束 {@code uk_request_approver} 兜底）</li>
 *   <li>事务标注：纯本地写方法标注 {@link Transactional}；
 *       {@link #executeApprovedWithdrawal} 跨服务调用 signing-service，标注
 *       {@link GlobalTransactional} + {@link Transactional} 保证原子性
 *       （设计文档 §4.5.2 事务边界分析）</li>
 * </ul>
 *
 * <p>跨服务调用：wallet-service 通过 Feign 调用 signing-service（设计文档 §3.2 方案 A），
 * 符合「wallet 管理审批、signing 负责签名」边界。原 exchange-wallet 中通过
 * {@code OnChainExecutionClient} 调 gateway 的链上执行通道已删除，改为直接调
 * signing-service 完成签名 + 广播。</p>
 *
 * <p>迁移历史：原位于 {@code org.nexus.wallet.wallet.approval.DefaultWithdrawalApprovalService}
 * （nexus-exchange-wallet），在 Phase 2 微服务化中迁移至 nexus-wallet-service
 * （新包 {@code org.nexus.walletsvc.approval}）。Phase 2 任务 #57 改造：
 * 删除对 {@code OnChainExecutionClient} 的依赖，改为注入 {@link SigningServiceFeignClient}。
 * Phase 4 任务 #72 改造：内存存储 → 数据库持久化 + Seata AT 事务接入。</p>
 */
@Service
public class DefaultWithdrawalApprovalService implements WithdrawalApprovalService {

    private static final Logger log = LoggerFactory.getLogger(DefaultWithdrawalApprovalService.class);

    /** 平台热钱包地址默认值 */
    private static final String DEFAULT_PLATFORM_WALLET_ADDRESS = "PLATFORM_HOT_WALLET";

    private final ApprovalPolicy approvalPolicy;
    private final SigningServiceFeignClient signingServiceClient;
    private final String platformWalletAddress;
    private final WithdrawalRequestRepository withdrawalRequestRepository;
    private final WithdrawalApproverRepository withdrawalApproverRepository;

    /**
     * 主构造器：注入审批策略、签名服务 Feign 客户端、平台钱包地址、
     * 提现请求 Repository、审批人 Repository（Phase 4 任务 #72，设计文档 §4.4.3）。
     *
     * @param approvalPolicy              审批策略（白名单校验 + 所需审批人数）
     * @param signingServiceClient        signing-service Feign 客户端（可为 null，fallback SIMULATED txHash）
     * @param platformWalletAddress       平台热钱包地址（@Value 注入，空则回退默认值）
     * @param withdrawalRequestRepository 提现请求持久化 Repository
     * @param withdrawalApproverRepository 提现审批人持久化 Repository
     */
    @Autowired
    public DefaultWithdrawalApprovalService(ApprovalPolicy approvalPolicy,
                                             SigningServiceFeignClient signingServiceClient,
                                             @Value("${nexus.wallet.platform-address:PLATFORM_HOT_WALLET}") String platformWalletAddress,
                                             WithdrawalRequestRepository withdrawalRequestRepository,
                                             WithdrawalApproverRepository withdrawalApproverRepository) {
        this.approvalPolicy = approvalPolicy;
        this.signingServiceClient = signingServiceClient;
        this.platformWalletAddress = (platformWalletAddress == null || platformWalletAddress.isEmpty())
                ? DEFAULT_PLATFORM_WALLET_ADDRESS : platformWalletAddress;
        this.withdrawalRequestRepository = withdrawalRequestRepository;
        this.withdrawalApproverRepository = withdrawalApproverRepository;
    }

    @Override
    @Transactional
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

        // DTO → Entity → 持久化（createdAt / updatedAt 由 Entity @PrePersist 自动维护）
        WithdrawalRequestEntity entity = WithdrawalRequestMapper.toEntity(request);
        WithdrawalRequestEntity saved = withdrawalRequestRepository.save(entity);

        log.info("Withdrawal requested: requestId={}, to={}, amount={}, requiredApprovers={}",
                saved.getRequestId(), to, amount, saved.getRequiredApprovers());
        // 新建请求无审批人，返回空 approvers 列表
        return WithdrawalRequestMapper.toDto(saved, Collections.emptyList());
    }

    @Override
    @Transactional
    public WithdrawalRequest approve(String approvalId, String approverId) {
        if (approvalId == null || approverId == null) {
            throw new IllegalArgumentException("approvalId and approverId are required");
        }
        WithdrawalRequestEntity entity = withdrawalRequestRepository.findByRequestId(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal request not found: " + approvalId));
        if (entity.getStatus() != WithdrawalRequest.WithdrawalStatus.PENDING) {
            throw new IllegalStateException("request is not pending: status=" + entity.getStatus());
        }
        // 防重复审批：Repository 查询（数据库唯一约束 uk_request_approver 兜底）
        if (withdrawalApproverRepository.existsByRequestIdAndApproverId(approvalId, approverId)) {
            throw new IllegalStateException("approver already approved: " + approverId);
        }

        // 插入审批人记录
        WithdrawalApproverEntity approver = new WithdrawalApproverEntity();
        approver.setRequestId(approvalId);
        approver.setApproverId(approverId);
        approver.setApprovedAt(LocalDateTime.now());
        withdrawalApproverRepository.save(approver);

        // 按 DB 实际计数更新 approvedCount（避免并发计数漂移）
        long newCount = withdrawalApproverRepository.countByRequestId(approvalId);
        entity.setApprovedCount((int) newCount);

        if (newCount >= entity.getRequiredApprovers()) {
            entity.setStatus(WithdrawalRequest.WithdrawalStatus.APPROVED);
            log.info("Withdrawal approved: requestId={}, approvers={}", approvalId, newCount);
        } else {
            log.info("Withdrawal approval recorded: requestId={}, approver={}, count={}/{}",
                    approvalId, approverId, newCount, entity.getRequiredApprovers());
        }
        WithdrawalRequestEntity saved = withdrawalRequestRepository.save(entity);
        return WithdrawalRequestMapper.toDto(saved, withdrawalApproverRepository.findByRequestId(approvalId));
    }

    @Override
    @Transactional
    public WithdrawalRequest reject(String approvalId, String approverId, String reason) {
        if (approvalId == null || approverId == null) {
            throw new IllegalArgumentException("approvalId and approverId are required");
        }
        WithdrawalRequestEntity entity = withdrawalRequestRepository.findByRequestId(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal request not found: " + approvalId));
        if (entity.getStatus() != WithdrawalRequest.WithdrawalStatus.PENDING) {
            throw new IllegalStateException("request is not pending: status=" + entity.getStatus());
        }

        entity.setStatus(WithdrawalRequest.WithdrawalStatus.REJECTED);
        entity.setRejectionReason(reason == null ? "rejected by " + approverId : reason);
        WithdrawalRequestEntity saved = withdrawalRequestRepository.save(entity);
        log.info("Withdrawal rejected: requestId={}, by={}, reason={}",
                approvalId, approverId, saved.getRejectionReason());
        return WithdrawalRequestMapper.toDto(saved, withdrawalApproverRepository.findByRequestId(approvalId));
    }

    /**
     * 执行已批准的提现：调 signing-service 签名广播，更新状态为 EXECUTED / FAILED。
     *
     * <p><strong>事务边界</strong>（设计文档 §4.5.2）：本方法跨服务调用 signing-service，
     * 标注 {@link GlobalTransactional} + {@link Transactional}：
     * <ul>
     *   <li>场景 A（gateway 退款流程）：gateway 已开启全局事务，xid 通过 Feign header 传播，
     *       本服务加入当前全局事务（{@code @GlobalTransactional} 退化为分支事务参与方）</li>
     *   <li>场景 B（管理后台直接调）：无上游全局事务，本服务作为 TM 新开启全局事务，
     *       signing-service 作为 RM 加入</li>
     * </ul>
     * {@code timeoutMills=120000}（2 分钟）覆盖链上签名广播耗时；
     * {@code rollbackFor=Exception.class} 保证任何异常都触发全局回滚
     * （undo_log 自动还原 withdrawal_requests 状态变更）。</p>
     *
     * <p>注意：方法内部 catch Exception 后置 FAILED 并返回（不重新抛出），
     * 此路径下事务正常提交（FAILED 状态持久化）。仅当方法抛出未捕获异常时才触发回滚。
     * 这是有意设计——签名失败时保留 FAILED 记录供后续排查，而非回滚到 APPROVED。</p>
     */
    @Override
    @GlobalTransactional(timeoutMills = 120000, rollbackFor = Exception.class)
    @Transactional
    public WithdrawalRequest executeApprovedWithdrawal(String approvalId) {
        if (approvalId == null) {
            throw new IllegalArgumentException("approvalId is required");
        }
        WithdrawalRequestEntity entity = withdrawalRequestRepository.findByRequestId(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal request not found: " + approvalId));
        if (entity.getStatus() != WithdrawalRequest.WithdrawalStatus.APPROVED) {
            throw new IllegalStateException("request is not approved: status=" + entity.getStatus());
        }

        try {
            String txHash;
            if (signingServiceClient != null) {
                // 通过 Feign 调用 signing-service 的 /api/v1/transfers/sign 端点
                // signing-service 使用平台密钥库完成签名 + 广播，返回交易哈希
                String result = signingServiceClient.signTransfer(
                        platformWalletAddress,
                        entity.getToAddress(),
                        entity.getAmount());
                if (result == null || result.isEmpty()) {
                    entity.setStatus(WithdrawalRequest.WithdrawalStatus.FAILED);
                    entity.setRejectionReason("signing service returned empty result");
                    withdrawalRequestRepository.save(entity);
                    log.error("Withdrawal execution failed: requestId={}, signing service returned empty", approvalId);
                    return WithdrawalRequestMapper.toDto(entity,
                            withdrawalApproverRepository.findByRequestId(approvalId));
                }
                txHash = result;
                log.info("Withdrawal executed via signing-service: requestId={}, txHash={}",
                        approvalId, txHash);
            } else {
                // Fail-closed（资金安全）：签名服务客户端未注入时不伪造 SIMULATED 哈希。
                // 提币涉及真实资金，缺失签名通道必须标记 FAILED 并拒绝放行，
                // 由上层按 FAILED 状态告警 / 人工介入，绝不把未上链的提币记为 EXECUTED。
                entity.setStatus(WithdrawalRequest.WithdrawalStatus.FAILED);
                entity.setRejectionReason("signing service client not configured; withdrawal aborted (fail-closed)");
                withdrawalRequestRepository.save(entity);
                log.error("Withdrawal aborted (fail-closed): no signing service client, requestId={}", approvalId);
                return WithdrawalRequestMapper.toDto(entity,
                        withdrawalApproverRepository.findByRequestId(approvalId));
            }
            entity.setChainTxHash(txHash);
            entity.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTED);
            entity.setExecutedAt(LocalDateTime.now());
        } catch (Exception e) {
            entity.setStatus(WithdrawalRequest.WithdrawalStatus.FAILED);
            entity.setRejectionReason("execution failed: " + e.getMessage());
            log.error("Withdrawal execution failed: requestId={}", approvalId, e);
        }
        WithdrawalRequestEntity saved = withdrawalRequestRepository.save(entity);
        return WithdrawalRequestMapper.toDto(saved, withdrawalApproverRepository.findByRequestId(approvalId));
    }

    /**
     * Query a withdrawal request by ID（含审批人列表）。
     *
     * <p>Phase 4 任务 #72 改造：从内存 {@code requests.get} 改为
     * {@link WithdrawalRequestRepository#findByRequestId} + 加载审批人列表
     * + {@link WithdrawalRequestMapper#toDto} 转换。</p>
     *
     * @param requestId request ID
     * @return the request (含 approvers)，或 null if not found
     */
    public WithdrawalRequest getRequest(String requestId) {
        if (requestId == null) {
            return null;
        }
        return withdrawalRequestRepository.findByRequestId(requestId)
                .map(entity -> WithdrawalRequestMapper.toDto(
                        entity, withdrawalApproverRepository.findByRequestId(requestId)))
                .orElse(null);
    }

    /**
     * 列出所有待审批（PENDING）的提现请求，按创建时间倒序排列。
     *
     * <p>Phase 4 任务 #72 新增（设计文档 §4.4.3）：供管理后台待审批列表展示，
     * 命中索引 {@code idx_status (status)}，逐个加载审批人列表后转换为 DTO。</p>
     *
     * @return PENDING 状态的提现请求列表（最新优先），不含审批人列表时返回空列表
     */
    public List<WithdrawalRequest> listPending() {
        List<WithdrawalRequestEntity> entities = withdrawalRequestRepository
                .findByStatusOrderByCreatedAtDesc(WithdrawalRequest.WithdrawalStatus.PENDING);
        return entities.stream()
                .map(entity -> WithdrawalRequestMapper.toDto(
                        entity, withdrawalApproverRepository.findByRequestId(entity.getRequestId())))
                .collect(Collectors.toList());
    }
}
