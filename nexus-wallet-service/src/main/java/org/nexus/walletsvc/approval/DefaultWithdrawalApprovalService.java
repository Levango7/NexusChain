package org.nexus.walletsvc.approval;

import io.seata.spring.annotation.GlobalTransactional;
import org.nexus.sdk.client.feign.SigningServiceFeignClient;
import org.nexus.sdk.client.feign.SigningResponses;
import org.nexus.sdk.signing.ApprovalPolicy;
import org.nexus.sdk.wallet.WithdrawalRequest;
import org.nexus.walletsvc.entity.WithdrawalApproverEntity;
import org.nexus.walletsvc.entity.WithdrawalRequestEntity;
import org.nexus.walletsvc.entity.WithdrawalRequestMapper;
import org.nexus.walletsvc.execution.ExecutionRequest;
import org.nexus.walletsvc.execution.OnChainResult;
import org.nexus.walletsvc.execution.ThreePhaseExecutionTemplate;
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
    /**
     * 平台热钱包地址的**占位符字面量**（2026-10-27 语义修正）。
     *
     * <p>原实现把它当"未配置时的回退默认值"——真实部署（helm/compose 均不注入
     * {@code NEX_WALLET_PLATFORM_ADDRESS}）因此会把该字面量当作平台地址发给 signing，
     * 提现必然以不可辨认的原因失败（与 #269 "cold-sweep" 同型）。
     * 现语义：**它只是"看起来像地址但实际不是"的标记值**——</p>
     * <ul>
     *   <li>未配置（空）⇒ 执行前 fail-closed 拒绝（{@link #executeOnChain}）；</li>
     *   <li>显式配成该字面量 ⇒ 放行但打 WARN（部署方可能照抄了示例）。</li>
     * </ul>
     */
    private static final String PLACEHOLDER_PLATFORM_WALLET_ADDRESS = "PLATFORM_HOT_WALLET";

    private final ApprovalPolicy approvalPolicy;
    private final SigningServiceFeignClient signingServiceClient;
    private final String platformWalletAddress;
    private final WithdrawalRequestRepository withdrawalRequestRepository;
    private final WithdrawalApproverRepository withdrawalApproverRepository;

    /**
     * 审计（P0 补齐 2026-10-11，setter 注入）。
     *
     * <p>提现是钱包侧唯一的资金出口，四个关键节点都要留痕（请求/审批/执行/失败）。
     * 用 setter 注入：本类已有两个构造器，再加参数会重蹈"多构造器"的坑
     * （2026-10-11 网关侧 `No default constructor found` 事故）。</p>
     */
    private org.nexus.walletsvc.audit.WalletAuditService walletAuditService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setWalletAuditService(org.nexus.walletsvc.audit.WalletAuditService walletAuditService) {
        this.walletAuditService = walletAuditService;
    }
    /** P2-F3：三阶段执行模板（落库 PENDING → 链上签名广播 → 更新 EXECUTED/FAILED） */
    private final ThreePhaseExecutionTemplate threePhaseTemplate;

    /**
     * 主构造器：注入审批策略、签名服务 Feign 客户端、平台钱包地址、
     * 提现请求 Repository、审批人 Repository（Phase 4 任务 #72，设计文档 §4.4.3）。
     *
     * @param approvalPolicy              审批策略（白名单校验 + 所需审批人数）
     * @param signingServiceClient        signing-service Feign 客户端（可为 null，fallback SIMULATED txHash）
     * @param platformWalletAddress       平台热钱包地址（@Value 注入；**空 = 未配置**，
     *                                    执行前 fail-closed 拒绝，不再回退占位符字面量）
     * @param withdrawalRequestRepository 提现请求持久化 Repository
     * @param withdrawalApproverRepository 提现审批人持久化 Repository
     * @param threePhaseTemplate          三阶段执行模板（P2-F3）
     */
    @Autowired
    public DefaultWithdrawalApprovalService(ApprovalPolicy approvalPolicy,
                                             SigningServiceFeignClient signingServiceClient,
                                             @Value("${nexus.wallet.platform-address:}") String platformWalletAddress,
                                             WithdrawalRequestRepository withdrawalRequestRepository,
                                             WithdrawalApproverRepository withdrawalApproverRepository,
                                             ThreePhaseExecutionTemplate threePhaseTemplate) {
        this.approvalPolicy = approvalPolicy;
        this.signingServiceClient = signingServiceClient;
        // 2026-10-27：不再回退占位符字面量——空保持为空，执行前 fail-closed 拒绝
        //（占位符当默认值会让真实部署把假地址发给 signing，失败原因不可辨认）。
        this.platformWalletAddress = platformWalletAddress == null ? "" : platformWalletAddress.trim();
        this.withdrawalRequestRepository = withdrawalRequestRepository;
        this.withdrawalApproverRepository = withdrawalApproverRepository;
        this.threePhaseTemplate = threePhaseTemplate;
    }

    /**
     * 测试用兼容构造器：不注入三阶段模板。
     *
     * <p>仅供不涉及 executeApprovedWithdrawal() 三阶段执行的测试使用。
     * executeApprovedWithdrawal() 在此构造器下会降级为内联三阶段逻辑
     * （无 REQUIRES_NEW 事务，但保持三阶段语义）。</p>
     */
    public DefaultWithdrawalApprovalService(ApprovalPolicy approvalPolicy,
                                             SigningServiceFeignClient signingServiceClient,
                                             String platformWalletAddress,
                                             WithdrawalRequestRepository withdrawalRequestRepository,
                                             WithdrawalApproverRepository withdrawalApproverRepository) {
        this(approvalPolicy, signingServiceClient, platformWalletAddress,
                withdrawalRequestRepository, withdrawalApproverRepository, null);
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

        audit("WITHDRAWAL_REQUESTED", entity, "requiredApprovers=" + saved.getRequiredApprovers());
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
            audit("WITHDRAWAL_APPROVED", entity, "approvers=" + newCount);
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
     * 执行已批准的提现：三阶段补偿模式（P2-F3）。
     *
     * <p><strong>三阶段执行</strong>（P2-F3 事务边界补偿模式重设计）：
     * <ol>
     *   <li>阶段1：落库 PENDING（实体状态保持 APPROVED，记录执行意图，
     *       实际状态变更在阶段3）</li>
     *   <li>阶段2：调 signing-service 签名广播（事务外，不可逆）</li>
     *   <li>阶段3：根据链上结果更新 EXECUTED / FAILED</li>
     * </ol>
     * 阶段1/3 通过 {@link ThreePhaseExecutionTemplate} 内部 REQUIRES_NEW 事务独立提交，
     * 阶段2 在事务外执行。{@code @GlobalTransactional} 协调跨服务分支。</p>
     *
     * <p><strong>事务边界</strong>（设计文档 §4.5.2）：
     * {@code timeoutMills=120000}（2 分钟）覆盖链上签名广播耗时；
     * {@code rollbackFor=Exception.class} 保证校验异常触发全局回滚。
     * 阶段2 链上执行异常被模板捕获并转为 FAILED 结果，不重抛
     * （链上不可逆，由 CompensationService 后续补偿）。</p>
     *
     * <p><strong>P1-F3 兼容</strong>：原 catch Exception 后置 FAILED 落库并重抛的语义
     * 由三阶段模板的阶段3 保证。阶段2 异常被模板捕获转为 OnChainResult.failure，
     * 阶段3 据此更新 FAILED。原重抛异常触发全局回滚的语义改为：
     * 阶段3 落库 FAILED 后正常返回，由 CompensationService 异步补偿。
     * 这避免了链上已广播但全局回滚导致的状态不一致。</p>
     */
    @Override
    @GlobalTransactional(timeoutMills = 120000, rollbackFor = Exception.class)
    public WithdrawalRequest executeApprovedWithdrawal(String approvalId) {
        if (approvalId == null) {
            throw new IllegalArgumentException("approvalId is required");
        }
        WithdrawalRequestEntity entity = withdrawalRequestRepository.findByRequestId(approvalId)
                .orElseThrow(() -> new IllegalArgumentException("withdrawal request not found: " + approvalId));

        // 原子抢占（2026-10-12 修复「并发/重复执行导致重复广播」）：
        // 原实现是「读 → 判 status == APPROVED」，两个并发调用都能通过，随后**各自**调用
        // signing-service 完成一次签名广播 ⇒ 同一笔提现可能被放款两次。乐观锁 @Version 只在
        // 阶段3写回时才冲突，而那时链上已广播，拦不住。
        // 现在改成条件 UPDATE（APPROVED → EXECUTING）抢占：数据库行级锁保证只有一个调用
        // 拿到 1 行；其余拿到 0 行的直接抛错返回，不触碰 signing-service。
        int claimed = withdrawalRequestRepository.compareAndSetStatus(
                approvalId,
                WithdrawalRequest.WithdrawalStatus.APPROVED,
                WithdrawalRequest.WithdrawalStatus.EXECUTING);
        if (claimed != 1) {
            // (c)-lite（2026-10-27）：允许从 AWAITING_APPROVAL 重入——复用既有 signing 审批
            // （阶段2 走查询/executeApproved，不再新建审批单）。悬空审批随重试线性增长
            // 正是本改造要消灭的缺陷（板 20261025-0415 §五 / 20261027-0306）。
            claimed = withdrawalRequestRepository.compareAndSetStatus(
                    approvalId,
                    WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL,
                    WithdrawalRequest.WithdrawalStatus.EXECUTING);
        }
        if (claimed != 1) {
            WithdrawalRequest.WithdrawalStatus current = withdrawalRequestRepository
                    .findByRequestId(approvalId)
                    .map(WithdrawalRequestEntity::getStatus)
                    .orElse(null);
            throw new IllegalStateException(
                    "withdrawal is not claimable (concurrent execution or wrong status): status=" + current);
        }
        // 让内存实体与库内状态一致（批量 UPDATE 绕过了持久化上下文；阶段3会再覆盖为 EXECUTED/FAILED）
        entity.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTING);

        // P2-F3：使用三阶段执行模板
        // 如果 threePhaseTemplate 为 null（测试构造器），降级为内联三阶段逻辑
        if (threePhaseTemplate != null) {
            return executeWithThreePhaseTemplate(approvalId, entity);
        } else {
            return executeInline(approvalId, entity);
        }
    }

    /**
     * 使用三阶段模板执行提现。
     */
    private WithdrawalRequest executeWithThreePhaseTemplate(String approvalId, WithdrawalRequestEntity entity) {
        ExecutionRequest executionRequest = new ExecutionRequest(
                ExecutionRequest.OperationType.WITHDRAWAL,
                entity.getAmount(),
                entity.getToAddress(),
                platformWalletAddress,
                entity.getRequestId(),  // 幂等键：提现请求 ID 唯一
                entity.getCurrency(),
                entity.getRequestId());

        threePhaseTemplate.execute(
                executionRequest,
                // 阶段1：落库 PENDING（保持 APPROVED 状态，记录执行意图）
                req -> entity,
                // 阶段2：链上签名广播（事务外）——(c)-lite 四态 + signing 审批复用
                e -> executeOnChain(e),
                // 阶段3：按结果落 EXECUTED / AWAITING_APPROVAL / UNVERIFIED / FAILED
                (e, onChainResult) -> {
                    applyExecutionOutcome(e, onChainResult, approvalId);
                    withdrawalRequestRepository.save(e);
                });

        return WithdrawalRequestMapper.toDto(entity,
                withdrawalApproverRepository.findByRequestId(approvalId));
    }

    /**
     * 内联三阶段执行（测试降级路径，threePhaseTemplate 为 null 时使用）。
     *
     * <p>保持与三阶段模板相同的语义，但不使用 REQUIRES_NEW 事务
     * （测试环境无事务管理器）。P1-F3 的 catch-重抛语义保留。</p>
     */
    private WithdrawalRequest executeInline(String approvalId, WithdrawalRequestEntity entity) {
        try {
            if (signingServiceClient == null) {
                OnChainResult failClosed = OnChainResult.failure(
                        "signing service client not configured; withdrawal aborted (fail-closed)", false);
                applyExecutionOutcome(entity, failClosed, approvalId);
                withdrawalRequestRepository.save(entity);
                log.error("Withdrawal aborted (fail-closed): no signing service client, requestId={}", approvalId);
                return WithdrawalRequestMapper.toDto(entity,
                        withdrawalApproverRepository.findByRequestId(approvalId));
            }
            OnChainResult result = executeOnChain(entity);
            applyExecutionOutcome(entity, result, approvalId);
        } catch (Exception e) {
            // P1-F3 修复：先持久化 FAILED 状态供后续排查，再重抛异常
            entity.setStatus(WithdrawalRequest.WithdrawalStatus.FAILED);
            entity.setRejectionReason("execution failed: " + e.getMessage());
            withdrawalRequestRepository.save(entity);
            log.error("Withdrawal execution failed: requestId={}", approvalId, e);
            throw e;
        }
        WithdrawalRequestEntity saved = withdrawalRequestRepository.save(entity);
        return WithdrawalRequestMapper.toDto(saved, withdrawalApproverRepository.findByRequestId(approvalId));
    }

    /**
     * 阶段2：链上执行（(c)-lite 四态语义 + signing 审批复用）。
     *
     * <p>三态分类改走 {@link SigningResponses#classify}（不再用已废弃的
     * {@link SigningResponses#txHash}——它把 2001「待审批」与「签名失败」压成同一个 null，
     * 是执行腿"待审批被判 FAILED"的根因）。</p>
     *
     * <p><b>核心不变式（复用，不新建）</b>：实体已带 {@code signingApprovalId} 时走
     * {@link #resumeSigningApproval}——只查询/推进既有审批，**绝不再次调用 signTransfer**，
     * 故重试不再产生悬空审批单。</p>
     *
     * @param e 提现实体（可能已带 signingApprovalId）
     * @return 四态结果：SUCCESS / AWAITING_APPROVAL / UNVERIFIED / FAILED
     */
    private OnChainResult executeOnChain(WithdrawalRequestEntity e) {
        // 2026-10-27（板 20261027-0306 §五 独立缺陷）：平台地址未配置 ⇒ fail-closed 拒绝。
        // 原实现回退占位符字面量并把假地址发给 signing，失败原因不可辨认（与 #269 同型）。
        if (platformWalletAddress == null || platformWalletAddress.isBlank()) {
            return OnChainResult.failure(
                    "platform wallet address not configured "
                            + "(nexus.wallet.platform-address / NEX_WALLET_PLATFORM_ADDRESS): "
                            + "withdrawal aborted (fail-closed)", false);
        }
        if (PLACEHOLDER_PLATFORM_WALLET_ADDRESS.equals(platformWalletAddress)) {
            log.warn("platform wallet address equals the placeholder literal '{}' — it is NOT a real "
                            + "address; set NEX_WALLET_PLATFORM_ADDRESS for this deployment",
                    PLACEHOLDER_PLATFORM_WALLET_ADDRESS);
        }
        if (signingServiceClient == null) {
            return OnChainResult.failure(
                    "signing service client not configured; withdrawal aborted (fail-closed)", false);
        }
        String existingApprovalId = e.getSigningApprovalId();
        if (existingApprovalId != null && !existingApprovalId.isBlank()) {
            return resumeSigningApproval(existingApprovalId);
        }
        SigningResponses.Outcome outcome = SigningResponses.classify(signingServiceClient.signTransfer(
                platformWalletAddress,
                e.getToAddress(),
                e.getAmount()));
        if (outcome.isExecuted()) {
            return OnChainResult.success(outcome.txHash(), false);
        }
        if (outcome.isAwaitingApproval()) {
            return OnChainResult.awaitingApproval(outcome.approvalId(), false);
        }
        return OnChainResult.failure(
                outcome.message() != null ? outcome.message() : "signing service returned empty result", false);
    }

    /**
     * 重试路径：复用既有 signing 审批推进整笔提现（(c)-lite 核心不变式）。
     *
     * <p>按 signing 侧审批状态分派：</p>
     * <ul>
     *   <li>{@code PENDING/EXECUTING} → 保持 AWAITING_APPROVAL（**不新建审批单**）</li>
     *   <li>{@code APPROVED} → 调 sign-approved 执行，按响应再分派</li>
     *   <li>{@code EXECUTED} → UNVERIFIED（signing 已执行但本地无哈希 = 可能已动账，
     *       不得判 FAILED / 不得自动重放）</li>
     *   <li>{@code REJECTED/EXPIRED} 或审批已不存在（404/降级）→ FAILED（链上确认未动账，
     *       可安全判失败；理由是"审批不存在"而非"签名失败"）</li>
     * </ul>
     */
    private OnChainResult resumeSigningApproval(String signingApprovalId) {
        String status = SigningResponses.approvalStatus(
                signingServiceClient.getApprovalStatus(signingApprovalId));
        if (status == null) {
            return OnChainResult.failure(
                    "signing approval not found (expired or cleaned up): " + signingApprovalId, false);
        }
        switch (status) {
            case "PENDING":
            case "EXECUTING":
                return OnChainResult.awaitingApproval(signingApprovalId, false);
            case "APPROVED": {
                SigningResponses.Outcome outcome = SigningResponses.classify(
                        signingServiceClient.executeApproved(signingApprovalId));
                if (outcome.isExecuted()) {
                    return OnChainResult.success(outcome.txHash(), false);
                }
                if (outcome.isAwaitingApproval()) {
                    return OnChainResult.awaitingApproval(outcome.approvalId(), false);
                }
                return OnChainResult.failure(
                        outcome.message() != null ? outcome.message() : "approved signing failed", false);
            }
            case "EXECUTED":
                return OnChainResult.unverified(
                        "signing approval already EXECUTED but no local txHash: " + signingApprovalId, false);
            default:
                return OnChainResult.failure(
                        "signing approval " + status.toLowerCase(java.util.Locale.ROOT)
                                + ": " + signingApprovalId, false);
        }
    }

    /**
     * 阶段3：按四态结果落库 + 审计。
     *
     * <p>不变式：AWAITING_APPROVAL 与 UNVERIFIED 都是**非终态语义**（前者未动账、后者可能已动账），
     * 必须留痕并保持可重入/可核对，不得被压成 FAILED。</p>
     */
    private void applyExecutionOutcome(WithdrawalRequestEntity e, OnChainResult result, String requestId) {
        if (result.isSuccess()) {
            e.setChainTxHash(result.getTxHash());
            e.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTED);
            e.setExecutedAt(LocalDateTime.now());
            log.info("Withdrawal executed via signing-service: requestId={}, txHash={}",
                    requestId, result.getTxHash());
            audit("WITHDRAWAL_EXECUTED", e, "txHash=" + result.getTxHash());
            return;
        }
        if (result.isAwaitingApproval()) {
            e.setSigningApprovalId(result.getApprovalId());
            e.setStatus(WithdrawalRequest.WithdrawalStatus.AWAITING_APPROVAL);
            e.setRejectionReason(null);
            log.warn("Withdrawal awaiting signing approval: requestId={}, signingApprovalId={}",
                    requestId, result.getApprovalId());
            audit("WITHDRAWAL_AWAITING_SIGNING_APPROVAL", e,
                    "signingApprovalId=" + result.getApprovalId());
            return;
        }
        if (result.isUnverified()) {
            e.setStatus(WithdrawalRequest.WithdrawalStatus.UNVERIFIED);
            e.setRejectionReason(result.getError());
            log.error("Withdrawal UNVERIFIED (possible on-chain effect, manual check required): requestId={}, reason={}",
                    requestId, result.getError());
            audit("WITHDRAWAL_UNVERIFIED", e, "error=" + result.getError());
            return;
        }
        e.setStatus(WithdrawalRequest.WithdrawalStatus.FAILED);
        e.setRejectionReason(result.getError());
        log.error("Withdrawal execution failed: requestId={}, reason={}", requestId, result.getError());
        audit("WITHDRAWAL_FAILED", e, "error=" + result.getError());
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

    /** 写审计（旁路：WalletAuditService 自身已吞异常，这里再兜一层，确保主流程不受影响）。 */
    private void audit(String action, WithdrawalRequestEntity entity, String detail) {
        if (walletAuditService == null || entity == null) {
            return;
        }
        try {
            walletAuditService.record(action, entity.getRequestId(), entity.getToAddress(),
                    entity.getAmount(), entity.getCurrency(), detail);
        } catch (RuntimeException ignored) {
            // 审计绝不影响资金流程
        }
    }
}
