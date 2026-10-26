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
import java.util.Locale;
import java.util.Map;
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

    /**
     * 签名侧 statusCode：审批未通过（状态非 APPROVED）⇒ <b>确定未动账</b>。
     *
     * <p>对应 {@code TxController.signTransferApproved} 的 5000 分支。</p>
     */
    private static final int SIGNING_NOT_APPROVED = 5000;

    /**
     * 签名侧 statusCode：CAS 被并发请求抢走（另一路正在执行）⇒ <b>本次确定未动账</b>。
     *
     * <p>对应 {@code tryMarkExecuting} 返回 false 时的 5001 分支。</p>
     */
    private static final int SIGNING_EXECUTION_CONFLICT = 5001;

    /** {@code rejection_reason} 列宽（V1）。 */
    private static final int REJECTION_REASON_MAX = 256;

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
     * @param platformWalletAddress       平台热钱包地址（@Value 注入，空则回退默认值）
     * @param withdrawalRequestRepository 提现请求持久化 Repository
     * @param withdrawalApproverRepository 提现审批人持久化 Repository
     * @param threePhaseTemplate          三阶段执行模板（P2-F3）
     */
    @Autowired
    public DefaultWithdrawalApprovalService(ApprovalPolicy approvalPolicy,
                                             SigningServiceFeignClient signingServiceClient,
                                             @Value("${nexus.wallet.platform-address:PLATFORM_HOT_WALLET}") String platformWalletAddress,
                                             WithdrawalRequestRepository withdrawalRequestRepository,
                                             WithdrawalApproverRepository withdrawalApproverRepository,
                                             ThreePhaseExecutionTemplate threePhaseTemplate) {
        this.approvalPolicy = approvalPolicy;
        this.signingServiceClient = signingServiceClient;
        this.platformWalletAddress = (platformWalletAddress == null || platformWalletAddress.isEmpty())
                ? DEFAULT_PLATFORM_WALLET_ADDRESS : platformWalletAddress;
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
                // 阶段2：链上签名广播（事务外）
                e -> submitToSigning(e),
                // 阶段3：根据链上结果更新 EXECUTED / AWAITING_APPROVAL / FAILED
                (e, onChainResult) -> {
                    if (onChainResult.isSuccess()) {
                        e.setChainTxHash(onChainResult.getTxHash());
                        e.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTED);
                        e.setExecutedAt(LocalDateTime.now());
                        log.info("Withdrawal executed via signing-service: requestId={}, txHash={}",
                                approvalId, onChainResult.getTxHash());
                        audit("WITHDRAWAL_EXECUTED", e, "txHash=" + onChainResult.getTxHash());
                    } else if (onChainResult.isAwaitingApproval()) {
                        // (c)-lite（2026-10-27）：签名侧转人工审批，**钱没出**。
                        // 关键：不置 FAILED。FAILED 是终态（CAS 只认 APPROVED → EXECUTING），
                        // 置 FAILED 会让「钱没出去、单子却死了」，同时把签名侧那条审批
                        // 变成永远没人消费的悬空审批。
                        // 回到 APPROVED（语义=已批准待执行），并持久化 approvalId 供推进腿复用。
                        e.setSigningApprovalId(onChainResult.getApprovalId());
                        e.setStatus(WithdrawalRequest.WithdrawalStatus.APPROVED);
                        e.setRejectionReason(null);
                        log.info("Withdrawal awaiting signing approval: requestId={}, signingApprovalId={}",
                                approvalId, onChainResult.getApprovalId());
                        audit("WITHDRAWAL_AWAITING_SIGNING_APPROVAL", e,
                                "signingApprovalId=" + onChainResult.getApprovalId());
                    } else if (onChainResult.isExecutedUnverified()) {
                        // 钱**可能已经出去**：落独立的 EXECUTED_UNVERIFIED 状态，
                        // 绝不记成「确定失败」——FAILED 会让运维按「没出账」处置，进而重复发起。
                        // 枚举新增值对持久化安全（@Enumerated(STRING)，无需迁移）。
                        e.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED);
                        e.setRejectionReason(truncate(onChainResult.getError(), REJECTION_REASON_MAX));
                        log.error("Withdrawal executed-but-unverified: requestId={}, reason={}",
                                approvalId, onChainResult.getError());
                        audit("WITHDRAWAL_EXECUTED_UNVERIFIED", e,
                                "reason=" + onChainResult.getError());
                    } else {
                        e.setStatus(WithdrawalRequest.WithdrawalStatus.FAILED);
                        e.setRejectionReason(truncate(onChainResult.getError(), REJECTION_REASON_MAX));
                        log.error("Withdrawal execution failed: requestId={}, reason={}",
                                approvalId, onChainResult.getError());
                        audit("WITHDRAWAL_FAILED", e, "error=" + onChainResult.getError());
                    }
                    withdrawalRequestRepository.save(e);
                });

        return WithdrawalRequestMapper.toDto(entity,
                withdrawalApproverRepository.findByRequestId(approvalId));
    }

    /**
     * 阶段2 实现：向签名侧提交转账，按三态处理（(c)-lite，2026-10-27）。
     *
     * <p><b>为什么必须区分三态</b>：signing-service 对 ≥ 大额阈值的转账走阻断式
     * 审批——不签名、返回 {@code statusCode=2001} + approvalId，且响应<b>没有
     * {@code data} 字段</b>。用 {@link SigningResponses#txHash} 取哈希必然得到
     * {@code null}，从而把「等审批」误判成「签名失败」：提现被置 FAILED，
     * 签名侧那条审批请求则永远没人消费（悬空审批）。</p>
     *
     * <p><b>幂等</b>：{@code entity.signingApprovalId} 非空表示这笔提现已向签名侧
     * 提交过、审批请求已存在，此时走 {@link #resumeSigning} 复用该 ID，
     * <b>绝不再调 {@code /transfers/sign}</b>——否则每次推进都会新建一条审批请求。</p>
     *
     * @param entity 提现实体（携带金额、目标地址、已有的 signingApprovalId）
     * @return 三态结果
     */
    private OnChainResult submitToSigning(WithdrawalRequestEntity entity) {
        if (signingServiceClient == null) {
            return OnChainResult.failure(
                    "signing service client not configured; withdrawal aborted (fail-closed)", false);
        }
        String existingApprovalId = entity.getSigningApprovalId();
        if (existingApprovalId != null && !existingApprovalId.isBlank()) {
            return resumeSigning(entity, existingApprovalId);
        }
        SigningResponses.Outcome outcome = SigningResponses.classify(
                signingServiceClient.signTransfer(
                        platformWalletAddress, entity.getToAddress(), entity.getAmount()));
        if (outcome.isAwaitingApproval()) {
            return OnChainResult.awaitingApproval(outcome.approvalId(), outcome.message());
        }
        if (outcome.isExecuted()) {
            return OnChainResult.success(outcome.txHash(), false);
        }
        return OnChainResult.failure(outcome.message(), false);
    }

    /**
     * 已有签名侧 approvalId 时的推进：先查审批状态，再决定执行 / 继续等 / 失败。
     *
     * <p>顺序按 SDK 契约（{@code SigningServiceFeignClient#getApprovalStatus} 的
     * javadoc）：查状态 → 仅在 {@code APPROVED} 时调 {@code executeApproved}。</p>
     *
     * <p><b>防双花</b>：signing 侧的审批状态会因 TTL 超时从 {@code EXECUTING}
     * 回退到 {@code APPROVED}。若上次执行其实已广播、只是响应丢失，直接重试会
     * 二次放款。因此 {@code signingExecutionAttemptedAt} 非空且本次又看到
     * {@code APPROVED} 时，判 FAILED 交人工核对，不盲目重试。</p>
     *
     * <p><b>fail-safe</b>：查不到状态（服务不可用 / 404 / 形状异常）一律按
     * 「继续等待」处理，不判失败——提现停在 APPROVED，下一轮推进还能再试。
     * 代价是若 approvalId 永久不存在，这笔会长期停在 APPROVED，需人工巡检发现。</p>
     */
    private OnChainResult resumeSigning(WithdrawalRequestEntity entity, String signingApprovalId) {
        Map<String, Object> statusResponse;
        try {
            statusResponse = signingServiceClient.getApprovalStatus(signingApprovalId);
        } catch (RuntimeException e) {
            // 查询失败不代表钱动了。Feign 在非 2xx（500、编解码错误等）时会抛
            // FeignException，而 fallback 只在熔断降级时生效——若不在这里兜住，
            // 异常会冒泡到三阶段模板的 catch 被记成「确定没出」的 FAILED，
            // 把一笔本可继续等待的提现判死。与 R1 同源：未知不能当确定。
            //
            // 代价（必须说清，别让它变成"静默无限等待"）：
            //   本分支返回 awaitingApproval ⇒ 提现停在 APPROVED。而 TTL 兜底依赖
            //   成功读到 data.expired / status=EXPIRED，**查询一直失败时永远读不到**
            //   ⇒ 这笔会永久停在 APPROVED，TTL 兜底在本路径下不生效。
            //   所以这里用 ERROR（不是 WARN）并写明需人工巡检，让运维能发现。
            log.error("getApprovalStatus threw; withdrawal will stay APPROVED and the TTL fallback"
                            + " cannot apply on this path (manual inspection required):"
                            + " signingApprovalId={}, error={}",
                    signingApprovalId, e.getMessage());
            return OnChainResult.awaitingApproval(signingApprovalId,
                    "signing approval status query failed (TTL fallback not applicable): "
                            + e.getMessage());
        }
        String signingStatus = signingStatusOf(statusResponse);
        if (signingStatus == null) {
            // 同上：fallback 返回 null 时也读不到 expired ⇒ TTL 兜底同样不生效。
            log.error("getApprovalStatus returned no status; withdrawal will stay APPROVED and the"
                            + " TTL fallback cannot apply on this path (manual inspection required):"
                            + " signingApprovalId={}", signingApprovalId);
            return OnChainResult.awaitingApproval(signingApprovalId,
                    "signing approval status unavailable (TTL fallback not applicable)");
        }
        // expired 是独立于 status 的标记（信封里 data.expired）。按 signing 侧
        // SigningApprovalRequest.isExpired() 的当前实现，expired 只在 status==PENDING 时为真，
        // 所以 status=APPROVED 时这个分支实际不会触发。保留它是防御性的：
        // 若将来 isExpired 的语义扩展到其他状态，已执行（EXECUTED）的请求不能因过期被否。
        if (!"EXECUTED".equals(signingStatus) && isExpired(statusResponse)) {
            return OnChainResult.failure("signing approval expired", false);
        }
        switch (signingStatus) {
            case "APPROVED":
                if (entity.getSigningExecutionAttemptedAt() != null) {
                    // 上次尝试过执行，signing 又回到 APPROVED ⇒ 上次可能已广播但结果丢失。
                    // 不重试（防双花），交人工核对。
                    // 注意这不是 FAILED：钱**可能已经出去**，记 FAILED 会让运维以为没出。
                    return OnChainResult.executedUnverified(
                            "signing approval returned to APPROVED after a prior execution attempt;"
                                    + " possible prior broadcast, manual reconciliation required");
                }
                entity.setSigningExecutionAttemptedAt(LocalDateTime.now());
                SigningResponses.Outcome outcome;
                try {
                    outcome = SigningResponses.classify(
                            signingServiceClient.executeApproved(signingApprovalId));
                } catch (RuntimeException e) {
                    // 执行请求已发出但抛了异常（编解码错误、超时配置缺失等 fallback 未覆盖的情形）：
                    // 结果不明 ⇒ 必须落 EXECUTED_UNVERIFIED，不能冒泡到三阶段模板的 catch
                    // 被记成「确定没出」的 FAILED。否则这正是本轮要修的「未知当失败」缺陷
                    // 在最后一处的残留（investigator 复核 R1）。
                    log.error("executeApproved threw: signingApprovalId={}, error={}",
                            signingApprovalId, e.getMessage(), e);
                    return OnChainResult.executedUnverified(
                            "executeApproved threw: " + e.getMessage()
                                    + "; manual reconciliation required");
                }
                if (outcome.isExecuted()) {
                    return OnChainResult.success(outcome.txHash(), false);
                }
                if (isDefinitelyNotExecuted(outcome)) {
                    // 5000（审批未通过）/ 5001（并发被另一路抢走）⇒ 本次确定未动账。
                    // 必须清掉 attempted 标记：否则下一次推进会被误判成
                    // 「上次可能已广播」而永久判死一笔从未广播的提现。
                    entity.setSigningExecutionAttemptedAt(null);
                    return OnChainResult.awaitingApproval(signingApprovalId,
                            "executeApproved not completed: " + outcome.message());
                }
                // 结果不明（网络失败 / 2000 但 data 缺失）：保留 attempted 标记，
                // 不写回 APPROVED（否则下次会被当成「可安全重试」而二次放款）。
                return OnChainResult.executedUnverified(
                        "executeApproved outcome unknown: " + outcome.message());
            case "EXECUTING":
                // 另一路正在执行，等它出结果。
                return OnChainResult.awaitingApproval(signingApprovalId, "signing approval executing");
            case "EXECUTED":
                // 签名侧已执行，但我们没拿到哈希 —— 钱已出，人工补录核对。
                return OnChainResult.executedUnverified(
                        "signing approval already EXECUTED but txHash unknown;"
                                + " manual reconciliation required");
            case "REJECTED":
            case "EXPIRED":
                return OnChainResult.failure("signing approval " + signingStatus.toLowerCase(Locale.ROOT), false);
            default:
                // PENDING 及其他：继续等待。
                return OnChainResult.awaitingApproval(signingApprovalId,
                        "signing approval status=" + signingStatus);
        }
    }

    /**
     * 从 {@code GET /signing/approvals/{id}} 的响应信封里取 {@code data.status}。
     *
     * <p>信封形状：{@code {statusCode:2000, data:{requestId, status, expired, amount, ...}}}；
     * 不存在时 HTTP 404 → Feign 走 fallback 返回 {@code null}。</p>
     *
     * @return 大写状态字符串；取不到时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    private static String signingStatusOf(Map<String, Object> statusResponse) {
        if (statusResponse == null) {
            return null;
        }
        Object data = statusResponse.get(SigningResponses.KEY_DATA);
        if (!(data instanceof Map)) {
            return null;
        }
        Object status = ((Map<String, Object>) data).get("status");
        if (status == null) {
            return null;
        }
        String text = status.toString().trim();
        return text.isEmpty() ? null : text.toUpperCase(Locale.ROOT);
    }

    /**
     * 审批信封是否标记为已过期（{@code data.expired}）。
     *
     * <p>该标记独立于 {@code status}：超时未收集够审批的请求会被标记 expired，
     * 此时即使状态字段仍写着 {@code APPROVED} 也不得执行。</p>
     */
    private static boolean isExpired(Map<String, Object> statusResponse) {
        if (statusResponse == null) {
            return false;
        }
        Object data = statusResponse.get(SigningResponses.KEY_DATA);
        if (!(data instanceof Map)) {
            return false;
        }
        Object expired = ((Map<?, ?>) data).get("expired");
        if (expired == null) {
            return false;
        }
        return Boolean.TRUE.equals(expired) || "true".equalsIgnoreCase(expired.toString().trim());
    }

    /**
     * 签名侧是否明确告知「本次未动账」。
     *
     * <p>只有 5000（审批未通过）与 5001（并发被抢）是确定的；其余（含响应为
     * {@code null} 的网络失败）都属「结果不明」，必须保留 attempted 标记。</p>
     */
    private static boolean isDefinitelyNotExecuted(SigningResponses.Outcome outcome) {
        Integer code = outcome.statusCode();
        return code != null
                && (code == SIGNING_NOT_APPROVED || code == SIGNING_EXECUTION_CONFLICT);
    }

    /** 按列宽截断（{@code rejection_reason} 为 VARCHAR(256)）。 */
    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    /**
     * 内联三阶段执行（测试降级路径，threePhaseTemplate 为 null 时使用）。
     *
     * <p>保持与三阶段模板相同的语义，但不使用 REQUIRES_NEW 事务
     * （测试环境无事务管理器）。P1-F3 的 catch-重抛语义保留。</p>
     */
    private WithdrawalRequest executeInline(String approvalId, WithdrawalRequestEntity entity) {
        try {
            // (c)-lite：与模板路径共用同一套三态分派，避免两条路径行为分叉
            // （qa-lead 指出：39 个单测全部走本路径，只改模板路径会「单测全绿而线上仍坏」）。
            OnChainResult result = submitToSigning(entity);
            if (result.isAwaitingApproval()) {
                // 签名侧转人工审批，钱没出：回 APPROVED 等待，持久化 approvalId。
                // 绝不置 FAILED（终态不可重入，会把「等审批」变成「单子死了」）。
                entity.setSigningApprovalId(result.getApprovalId());
                entity.setStatus(WithdrawalRequest.WithdrawalStatus.APPROVED);
                entity.setRejectionReason(null);
                log.info("Withdrawal awaiting signing approval (inline): requestId={}, signingApprovalId={}",
                        approvalId, result.getApprovalId());
                WithdrawalRequestEntity awaiting = withdrawalRequestRepository.save(entity);
                return WithdrawalRequestMapper.toDto(awaiting,
                        withdrawalApproverRepository.findByRequestId(approvalId));
            }
            if (result.isExecutedUnverified()) {
                // 钱可能已经出去：落独立状态，不记成「确定失败」。
                entity.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTED_UNVERIFIED);
                entity.setRejectionReason(truncate(result.getError(), REJECTION_REASON_MAX));
                withdrawalRequestRepository.save(entity);
                log.error("Withdrawal executed-but-unverified (inline): requestId={}, reason={}",
                        approvalId, result.getError());
                return WithdrawalRequestMapper.toDto(entity,
                        withdrawalApproverRepository.findByRequestId(approvalId));
            }
            if (!result.isSuccess()) {
                entity.setStatus(WithdrawalRequest.WithdrawalStatus.FAILED);
                entity.setRejectionReason(truncate(result.getError(), REJECTION_REASON_MAX));
                withdrawalRequestRepository.save(entity);
                log.error("Withdrawal execution failed: requestId={}, reason={}",
                        approvalId, result.getError());
                return WithdrawalRequestMapper.toDto(entity,
                        withdrawalApproverRepository.findByRequestId(approvalId));
            }
            entity.setChainTxHash(result.getTxHash());
            entity.setStatus(WithdrawalRequest.WithdrawalStatus.EXECUTED);
            entity.setExecutedAt(LocalDateTime.now());
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
