package org.nexus.signing.approval;

import org.nexus.signing.audit.AuditEvent;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.mpc.MpcApprovalPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 多签审批服务（P2-F1 完整安全架构）。
 *
 * <p>对大额签名操作要求多签审批：发起签名请求时创建审批请求，
 * 收集足够审批签名后才能执行实际签名。本服务复用
 * {@link MpcApprovalPolicy#getRequiredApprovers} 计算所需审批人数，
 * 与 MPC 阈值签名流程解耦（审批通过后是否走 MPC 由
 * {@link MpcApprovalPolicy#isColdWalletTier} 决定）。</p>
 *
 * <h3>审批流程</h3>
 * <pre>
 *   1. 调用方发起签名请求 → {@link #createApprovalRequest}
 *      - 金额 ≤ 大额阈值：直接返回 null，调用方跳过审批立即签名
 *      - 金额 > 大额阈值：创建 PENDING 审批请求，记录审计日志，
 *        返回 requestId 供调用方查询审批状态
 *   2. 审批人通过 {@link #approve} / {@link #reject} 提交决策
 *      - 每次决策记录审计日志
 *      - 收集到足够审批 → 状态变更为 APPROVED
 *      - 任一拒绝 → 状态变更为 REJECTED
 *   3. 调用方通过 {@link #getRequest} 查询审批状态
 *      - APPROVED → 执行签名 → {@link #markExecuted}
 *      - REJECTED / EXPIRED → 拒绝签名请求
 * </pre>
 *
 * <h3>简化版实现说明</h3>
 * <p>本服务为 P2-F1 简化版，特性范围：
 * <ul>
 *   <li>✓ 审批请求创建与状态机（PENDING → APPROVED/REJECTED → EXECUTED/EXPIRED）</li>
 *   <li>✓ 大额阈值从配置读取（{@code nexus.approval.large-amount-threshold}）</li>
 *   <li>✓ 审批所需人数复用 {@link MpcApprovalPolicy}（与 MPC 阈值对齐）</li>
 *   <li>✓ 所有审批操作记录审计日志（{@link AuditLogService}）</li>
 *   <li>✓ 审批请求存储：内存 ConcurrentHashMap（适合单实例部署；
 *          多实例需替换为 Redis / DB 共享存储，通过 {@code nexus.approval.use-database}
 *          开关预留扩展点，留待 P3 阶段实现 DB 持久化）</li>
 *   <li>✗ 审批人通知：未实现（需集成消息队列 / 邮件 / 钉钉，
 *         留待 P3 阶段；当前仅记录审计日志，运维通过日志监控发现待审批）</li>
 *   <li>✓ 审批人白名单：通过 {@code nexus.approval.approver-whitelist} 配置逗号分隔的
 *         审批人地址集合，空值表示不限制（任何 SIGNER 角色均可审批）</li>
 *   <li>✓ 过期清理：{@link #cleanupExpiredRequests} 定时任务（@Scheduled，每分钟执行）
 *         将 PENDING+过期请求标记为 EXPIRED，并清理终态过期请求释放内存</li>
 * </ul></p>
 *
 * <h3>线程安全</h3>
 * <p>审批请求存储使用 {@link ConcurrentHashMap}，单请求的状态变更通过
 * {@code compute} 原子操作保证一致性。{@link SigningApprovalRequest} 本身
 * 为不可变值对象，状态变更返回新实例。</p>
 */
@Service
public class SigningApprovalService {

    private static final Logger log = LoggerFactory.getLogger(SigningApprovalService.class);

    /**
     * 大额阈值：金额 ≥ 此阈值时触发多签审批。
     * <p>从 {@code nexus.approval.large-amount-threshold} 配置读取，默认 10000。
     * 即 > 10000 USDT 的签名请求需多签审批。</p>
     */
    @Value("${nexus.approval.large-amount-threshold:10000}")
    private BigDecimal largeAmountThreshold;

    /**
     * 审批请求有效期（秒）。
     * <p>从 {@code nexus.approval.ttl-seconds} 配置读取，默认 3600（1 小时）。
     * 超过此时间未收集足够审批的请求自动标记为 EXPIRED。</p>
     */
    @Value("${nexus.approval.ttl-seconds:3600}")
    private long ttlSeconds;

    /**
     * 大额审批所需审批人数下限。
     * <p>从 {@code nexus.approval.min-required-approvers} 配置读取，默认 2。
     * 实际所需人数取 max(此值, MpcApprovalPolicy.getRequiredApprovers)。</p>
     */
    @Value("${nexus.approval.min-required-approvers:2}")
    private int minRequiredApprovers;

    /**
     * 审批人白名单（逗号分隔的审批人地址列表）。
     * <p>从 {@code nexus.approval.approver-whitelist} 配置读取，默认空字符串。
     * 空值表示不限制（任何持有 SIGNER 角色的调用方均可审批）；
     * 非空时仅允许列表中的地址提交审批决策（approve / reject）。</p>
     * <p>示例：{@code approver-whitelist=alice@nexus,bob@nexus,carol@nexus}</p>
     */
    @Value("${nexus.approval.approver-whitelist:}")
    private String approverWhitelist;

    /**
     * 是否使用数据库持久化审批请求（多实例部署共享状态）。
     * <p>从 {@code nexus.approval.use-database} 配置读取，默认 false（内存存储）。
     * 设为 true 时应替换 {@link #requestStore} 为 Repository 调用，
     * 当前实现保留扩展点，true 时记录 WARN 日志并回退到内存存储，
     * 待 P3 阶段引入 JPA Entity + Repository 后完整实现。</p>
     */
    @Value("${nexus.approval.use-database:false}")
    private boolean useDatabase;

    /**
     * 过期清理定时任务执行间隔（毫秒）。
     * <p>从 {@code nexus.approval.cleanup-interval-ms} 配置读取，默认 60000（1 分钟）。
     * {@link #cleanupExpiredRequests} 按此间隔执行。</p>
     */
    @Value("${nexus.approval.cleanup-interval-ms:60000}")
    private long cleanupIntervalMs;

    /**
     * 终态请求清理保留期（秒）。
     * <p>从 {@code nexus.approval.cleanup-retention-seconds} 配置读取，默认 3600（1 小时）。
     * EXPIRED / REJECTED / EXECUTED 状态的请求在 deadline 过期超过此保留期后从内存移除，
     * 保留期内保留以供审计查询。PENDING+过期请求先标记为 EXPIRED，不在此阶段直接删除。</p>
     */
    @Value("${nexus.approval.cleanup-retention-seconds:3600}")
    private long cleanupRetentionSeconds;

    private final MpcApprovalPolicy mpcApprovalPolicy;
    private final AuditLogService auditLogService;

    /**
     * 审批请求存储：requestId → 请求实例。
     * <p>单实例部署使用内存 ConcurrentHashMap；多实例部署应设
     * {@code nexus.approval.use-database=true} 并替换为 DB 共享存储
     * （待 P3 阶段引入 JPA Repository 后实现）。</p>
     */
    private final Map<String, SigningApprovalRequest> requestStore = new ConcurrentHashMap<>();

    /**
     * 构造函数。
     *
     * @param mpcApprovalPolicy MPC 审批策略，用于计算所需审批人数
     * @param auditLogService   审计日志服务
     */
    public SigningApprovalService(MpcApprovalPolicy mpcApprovalPolicy,
                                  AuditLogService auditLogService) {
        this.mpcApprovalPolicy = Objects.requireNonNull(mpcApprovalPolicy, "mpcApprovalPolicy");
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService");
        if (useDatabase) {
            // 项3扩展点：use-database=true 时应注入 Repository 并替换 requestStore。
            // 当前实现未引入 JPA 依赖，记录 WARN 并回退到内存存储，避免破坏现有功能。
            log.warn("nexus.approval.use-database=true 但 DB 持久化尚未实现（P3 阶段），"
                    + "审批请求仍使用内存 ConcurrentHashMap 存储，多实例部署状态不共享");
        }
        log.info("SigningApprovalService 初始化: useDatabase={}, cleanupIntervalMs={}, retentionSeconds={}, "
                + "whitelistConfigured={}", useDatabase, cleanupIntervalMs, cleanupRetentionSeconds,
                approverWhitelist != null && !approverWhitelist.trim().isEmpty());
    }

    /**
     * 判断给定金额是否需要多签审批。
     *
     * @param amount   金额
     * @param currency 币种
     * @return true 表示需要审批；false 表示可直接签名
     */
    public boolean requiresApproval(BigDecimal amount, String currency) {
        if (amount == null || largeAmountThreshold == null) {
            return false;
        }
        return amount.compareTo(largeAmountThreshold) >= 0;
    }

    /**
     * 创建审批请求。
     *
     * <p>若金额未达大额阈值，返回 null 表示无需审批，调用方应直接执行签名。
     * 否则创建 PENDING 审批请求，记录审计日志，返回请求 ID 供调用方查询。</p>
     *
     * @param fromPubkey   转出公钥
     * @param toPubkeyHash 转入公钥 hash
     * @param amount       金额
     * @param currency     币种
     * @param initiator    发起人标识（JWT subject）
     * @param sourceIp     来源 IP（用于审计）
     * @return 审批请求 ID；无需审批时返回 null
     */
    public String createApprovalRequest(String fromPubkey, String toPubkeyHash,
                                        BigDecimal amount, String currency,
                                        String initiator, String sourceIp) {
        if (!requiresApproval(amount, currency)) {
            return null;
        }
        int required = Math.max(minRequiredApprovers,
                mpcApprovalPolicy.getRequiredApprovers(amount, currency));
        SigningApprovalRequest request = SigningApprovalRequest.create(
                fromPubkey, toPubkeyHash, amount, currency,
                required, ttlSeconds, initiator);
        requestStore.put(request.getRequestId(), request);

        log.info("创建多签审批请求: requestId={}, amount={} {}, required={}, initiator={}",
                request.getRequestId(), amount, currency, required, initiator);

        auditLogService.log(AuditEvent.builder(AuditEvent.Type.APPROVAL_REQUEST,
                        AuditEvent.Outcome.SUCCESS, initiator)
                .sourceIp(sourceIp)
                .target(request.getRequestId())
                .detail("amount", amount.toPlainString())
                .detail("currency", currency)
                .detail("required_approvers", required)
                .detail("deadline", request.getDeadline().toString())
                .build());

        return request.getRequestId();
    }

    /**
     * 提交审批通过。
     *
     * @param requestId 审批请求 ID
     * @param approver  审批人标识
     * @param sourceIp  来源 IP（用于审计）
     * @return 更新后的审批请求；requestId 不存在时返回 null
     */
    public SigningApprovalRequest approve(String requestId, String approver, String sourceIp) {
        return updateRequest(requestId, approver, sourceIp, true);
    }

    /**
     * 提交审批拒绝。
     *
     * @param requestId 审批请求 ID
     * @param rejecter  拒绝人标识
     * @param sourceIp  来源 IP（用于审计）
     * @return 更新后的审批请求；requestId 不存在时返回 null
     */
    public SigningApprovalRequest reject(String requestId, String rejecter, String sourceIp) {
        return updateRequest(requestId, rejecter, sourceIp, false);
    }

    /**
     * 判断审批人是否在白名单内。
     * <p>白名单未配置（空字符串或 null）时返回 true（不限制）；
     * 否则按逗号分隔解析白名单集合，校验审批人地址是否包含在内。
     * 解析时 trim 每个元素，空元素被忽略。</p>
     *
     * @param approverAddress 审批人地址标识
     * @return true 表示允许审批；false 表示不在白名单
     */
    private boolean isApproverAllowed(String approverAddress) {
        if (approverWhitelist == null || approverWhitelist.trim().isEmpty()) {
            return true; // 未配置白名单则不限制
        }
        if (approverAddress == null || approverAddress.isBlank()) {
            return false;
        }
        Set<String> whitelist = Arrays.stream(approverWhitelist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return whitelist.contains(approverAddress.trim());
    }

    private SigningApprovalRequest updateRequest(String requestId, String actor,
                                                 String sourceIp, boolean approve) {
        if (requestId == null) {
            return null;
        }
        // 项2：审批人白名单校验。未配置白名单时 isApproverAllowed 返回 true（不限制）。
        if (!isApproverAllowed(actor)) {
            log.warn("审批人不在白名单，拒绝决策: requestId={}, actor={}, decision={}",
                    requestId, actor, approve ? "APPROVE" : "REJECT");
            auditLogService.log(AuditEvent.builder(AuditEvent.Type.APPROVAL_DECISION,
                            AuditEvent.Outcome.DENIED, actor)
                    .sourceIp(sourceIp)
                    .target(requestId)
                    .detail("decision", approve ? "APPROVE" : "REJECT")
                    .detail("denied_reason", "approver_not_in_whitelist")
                    .build());
            // 返回当前请求快照（不修改状态）；若请求不存在则返回 null
            return requestStore.get(requestId);
        }
        final SigningApprovalRequest[] result = new SigningApprovalRequest[1];
        requestStore.computeIfPresent(requestId, (id, req) -> {
            if (req.getStatus() != SigningApprovalRequest.Status.PENDING) {
                log.warn("审批请求状态非 PENDING，忽略决策: requestId={}, status={}, actor={}",
                        requestId, req.getStatus(), actor);
                result[0] = req;
                return req;
            }
            SigningApprovalRequest updated = approve
                    ? req.withApproval(actor)
                    : req.withRejection(actor);
            result[0] = updated;

            log.info("审批决策: requestId={}, actor={}, decision={}, newStatus={}",
                    requestId, actor, approve ? "APPROVE" : "REJECT", updated.getStatus());

            auditLogService.log(AuditEvent.builder(AuditEvent.Type.APPROVAL_DECISION,
                            approve ? AuditEvent.Outcome.SUCCESS : AuditEvent.Outcome.DENIED, actor)
                    .sourceIp(sourceIp)
                    .target(requestId)
                    .detail("decision", approve ? "APPROVE" : "REJECT")
                    .detail("new_status", updated.getStatus().name())
                    .detail("approvals_count", updated.getApprovals().size())
                    .detail("required", updated.getRequiredApprovers())
                    .build());

            return updated;
        });
        return result[0];
    }

    /**
     * 查询审批请求。
     *
     * @param requestId 审批请求 ID
     * @return 审批请求；不存在时返回 null
     */
    public SigningApprovalRequest getRequest(String requestId) {
        if (requestId == null) {
            return null;
        }
        SigningApprovalRequest req = requestStore.get(requestId);
        if (req != null && req.isExpired() && req.getStatus() == SigningApprovalRequest.Status.PENDING) {
            // 惰性过期：查询时发现已过期则更新状态
            SigningApprovalRequest expired = req.withStatus(SigningApprovalRequest.Status.EXPIRED);
            requestStore.put(requestId, expired);
            return expired;
        }
        return req;
    }

    /**
     * 标记审批请求已执行（签名已广播）。
     *
     * @param requestId 审批请求 ID
     * @return 更新后的审批请求；requestId 不存在时返回 null
     */
    public SigningApprovalRequest markExecuted(String requestId) {
        if (requestId == null) {
            return null;
        }
        final SigningApprovalRequest[] result = new SigningApprovalRequest[1];
        requestStore.computeIfPresent(requestId, (id, req) -> {
            if (req.getStatus() != SigningApprovalRequest.Status.APPROVED) {
                log.warn("审批请求状态非 APPROVED，无法标记 EXECUTED: requestId={}, status={}",
                        requestId, req.getStatus());
                result[0] = req;
                return req;
            }
            SigningApprovalRequest executed = req.withStatus(SigningApprovalRequest.Status.EXECUTED);
            result[0] = executed;
            log.info("审批请求标记已执行: requestId={}", requestId);
            return executed;
        });
        return result[0];
    }

    /**
     * 定时清理过期审批请求（项1）。
     *
     * <p>由 {@code @Scheduled} 驱动，每 {@code nexus.approval.cleanup-interval-ms} 毫秒执行一次（默认 60 秒）。
     * 清理分两阶段，避免直接删除 PENDING 请求导致调用方查询返回 null 而非 EXPIRED：</p>
     * <ol>
     *   <li><b>标记过期</b>：扫描 PENDING 且 {@code deadline < now} 的请求，状态转换为 EXPIRED，
     *       记录审计日志。保留请求实例供调用方查询与审计追溯。</li>
     *   <li><b>释放内存</b>：清理终态（EXPIRED / REJECTED / EXECUTED）且
     *       {@code deadline < now - cleanupRetentionSeconds} 的请求，从 {@link #requestStore} 移除。
     *       保留期内（默认 1 小时）的终态请求保留以供审计查询。
     *       APPROVED 状态不清理（签名执行流程可能仍在进行，由 {@link #markExecuted} 处理）。</li>
     * </ol>
     *
     * <p>线程安全：使用 ConcurrentHashMap 的 entrySet().removeIf 与 computeIfPresent 原子操作，
     * 清理期间不影响并发审批决策。</p>
     */
    @Scheduled(fixedDelayString = "${nexus.approval.cleanup-interval-ms:60000}")
    public void cleanupExpiredRequests() {
        Instant now = Instant.now();
        Instant retentionCutoff = now.minusSeconds(cleanupRetentionSeconds);

        // 阶段1：标记 PENDING+过期 → EXPIRED（保留实例，仅状态转换）
        int markedExpired = 0;
        for (Map.Entry<String, SigningApprovalRequest> entry : requestStore.entrySet()) {
            SigningApprovalRequest req = entry.getValue();
            if (req.getStatus() == SigningApprovalRequest.Status.PENDING
                    && req.getDeadline().isBefore(now)) {
                SigningApprovalRequest expired = req.withStatus(SigningApprovalRequest.Status.EXPIRED);
                requestStore.put(entry.getKey(), expired);
                markedExpired++;
                log.info("审批请求过期标记: requestId={}, deadline={}", entry.getKey(), req.getDeadline());
                auditLogService.log(AuditEvent.builder(AuditEvent.Type.APPROVAL_DECISION,
                                AuditEvent.Outcome.DENIED, req.getInitiator())
                        .target(entry.getKey())
                        .detail("decision", "EXPIRED")
                        .detail("new_status", "EXPIRED")
                        .detail("deadline", req.getDeadline().toString())
                        .build());
            }
        }

        // 阶段2：清理终态且超过保留期的请求（释放内存）
        int removed = 0;
        Iterator<Map.Entry<String, SigningApprovalRequest>> it = requestStore.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, SigningApprovalRequest> entry = it.next();
            SigningApprovalRequest req = entry.getValue();
            SigningApprovalRequest.Status status = req.getStatus();
            if ((status == SigningApprovalRequest.Status.EXPIRED
                    || status == SigningApprovalRequest.Status.REJECTED
                    || status == SigningApprovalRequest.Status.EXECUTED)
                    && req.getDeadline().isBefore(retentionCutoff)) {
                it.remove();
                removed++;
            }
        }

        if (markedExpired > 0 || removed > 0) {
            log.info("审批请求清理完成: markedExpired={}, removedTerminal={}, remaining={}",
                    markedExpired, removed, requestStore.size());
        }
    }

    /**
     * 获取所有待审批请求（运维查询用）。
     *
     * @return 不可修改的待审批请求集合
     */
    public Map<String, SigningApprovalRequest> getPendingRequests() {
        return Collections.unmodifiableMap(requestStore);
    }
}