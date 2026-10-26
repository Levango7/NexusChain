package org.nexus.signing.approval;

import jakarta.annotation.PostConstruct;
import org.nexus.signing.audit.AuditEvent;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.mpc.MpcApprovalPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 *         审批人标识集合。<b>fail-closed</b>：空值/未配置 ⇒ 任何人都不能批（2026-10-17 改掉
 *         旧的"空值不限制"，见 {@link #approverDenialReason}）。要启用人工审批必须显式列名单</li>
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
     * <b>空值 = fail-closed</b>：所有 approve/reject 决策都会被拒绝，原因为
     * {@code approver_whitelist_not_configured}（#129）；非空时仅允许列表中的
     * 地址提交审批决策。</p>
     * <p>示例：{@code approver-whitelist=alice@nexus,bob@nexus,carol@nexus}</p>
     * <p>注意：本字段是 {@code @Value} 字段注入，构造器执行时尚未赋值，
     * 因此可见性检查放在 {@link #logApproverWhitelistVisibility()}（{@code @PostConstruct}）。</p>
     */
    @Value("${nexus.approval.approver-whitelist:}")
    private String approverWhitelist;

    /**
     * 大额审批豁免的转出地址（fromPubkey）名单，逗号分隔。
     * <p>从 {@code nexus.approval.exempt-from-pubkeys}（env {@code NEX_APPROVAL_EXEMPT_FROM_PUBKEYS}）
     * 读取，默认空字符串 ⇒ <b>无任何豁免（fail-closed）</b>，行为与未引入本配置前完全一致。</p>
     * <p>用途（2026-10-26 (b2) 止血裁决，留言板 20261025-0415 §五 / 20261025-0830 §一）：
     * 平台钱包转账的业务审批在 wallet-service 自身的多签流程里，执行腿再触发 signing
     * 大额审批属于两层重复审批，且 wallet 侧重试会留下悬空审批。列入本名单的转出地址
     * 跳过 signing 大额审批；<b>控制并未丢失</b>——豁免的前提是业务层（wallet 多签）
     * 已完成审批。名单按公钥原文精确匹配（区分大小写，仅对两侧 trim 空白）。
     * 目标态为 (a)（signing 提供显式的"业务已批准"契约），本配置是止血期方案，
     * 落地 (a) 后应迁移并废弃本名单。</p>
     */
    @Value("${nexus.approval.exempt-from-pubkeys:}")
    private String exemptFromPubkeys;

    /**
     * 是否使用数据库持久化审批请求（多实例部署共享状态）——遗留开关。
     *
     * <p>从 {@code nexus.approval.use-database} 配置读取，默认 false（内存存储）。
     * v2.31.0（任务 #375）起推荐使用 {@link #storeType} 显式选择存储类型；
     * 本开关仅在 store-type 未显式配置时生效：true → jpa，false → memory。</p>
     *
     * <p>由构造器参数注入（修复原 @Value 字段注入在构造器执行后才生效、
     * 导致构造期读取恒为 false 的时序缺陷）。</p>
     */
    private final boolean useDatabase;

    /**
     * 审批存储类型显式选择。
     *
     * <p>从 {@code nexus.approval.store-type} 配置读取，可选值：
     * {@code memory}（内存）/ {@code file}（JSONL 文件）/ {@code jpa}（数据库）。
     * 空值表示回落到 {@link #useDatabase} 遗留开关语义。</p>
     */
    private final String storeType;

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

    /**
     * 审批记录文件持久化路径（store-type=file 或遗留 use-database=true 时生效）。
     * 默认 data/approval-records.jsonl（相对于工作目录）。
     * 由构造器参数注入，避免 @Value 字段注入的构造期时序问题。
     */
    private final String approvalDataFilePath;

    private final MpcApprovalPolicy mpcApprovalPolicy;
    private final AuditLogService auditLogService;

    /**

     * 审批请求存储。
     *
     * <p>根据 {@code nexus.approval.use-database} 配置选择实现：
     * <ul>
     *   <li>{@code false}（默认）：使用内存 {@link ConcurrentHashMap}，适合单实例部署</li>
     *   <li>{@code true}：使用 {@link FileBasedApprovalStore} 文件持久化，
     *       审批记录存于 {@code data/approval-records.jsonl}，重启不丢失</li>
     * </ul>
     */
    private final ApprovalStore requestStore;

    /**
     * 审批人通知器。
     *
     * <p>审批请求创建时通过此通知器通知审批人有待审批项。
     * 默认使用 {@link LoggingApprovalNotifier}（记录 WARN 日志），
     * 可通过实现 {@link ApprovalNotifier} 接口并注册为 Spring Bean 自定义。
     */
    private final ApprovalNotifier approvalNotifier;

    /**
     * 简化构造函数（单元测试手动构造用）。
     *
     * <p>行为约定：不配置任何开关参数 → 内存存储（默认行为，向后兼容）。</p>
     *
     * @param mpcApprovalPolicy MPC 审批策略，用于计算所需审批人数
     * @param auditLogService   审计日志服务
     */
    public SigningApprovalService(MpcApprovalPolicy mpcApprovalPolicy,
                                  AuditLogService auditLogService) {
        this(mpcApprovalPolicy, auditLogService, null, null, "", false,
                "data/approval-records.jsonl", null);
    }

    /**
     * Spring 自动装配构造函数。
     *
     * <p>存储选择参数通过构造器参数注入（而非 @Value 字段注入），确保
     * store 选择逻辑执行时配置已就绪——修复原实现中 @Value 字段在构造器
     * 执行后才注入、导致 {@code use-database} 判断恒为 false 的时序缺陷。</p>
     *
     * @param mpcApprovalPolicy   MPC 审批策略
     * @param auditLogService     审计日志服务
     * @param repositoryProvider  JPA Repository 提供者（JPA 未启用时 getIfAvailable 返回 null）
     * @param storeTypeConfigured 显式存储类型（nexus.approval.store-type，空值回落遗留开关）
     * @param useDatabaseLegacy   遗留开关（nexus.approval.use-database）
     * @param filePathConfigured  文件存储路径（nexus.approval.persistence.file-path）
     */
    @org.springframework.beans.factory.annotation.Autowired
    public SigningApprovalService(MpcApprovalPolicy mpcApprovalPolicy,
                                  AuditLogService auditLogService,
                                  org.springframework.beans.factory.ObjectProvider<SigningApprovalRequestRepository> repositoryProvider,
                                  @Value("${nexus.approval.store-type:}") String storeTypeConfigured,
                                  @Value("${nexus.approval.use-database:false}") boolean useDatabaseLegacy,
                                  @Value("${nexus.approval.persistence.file-path:data/approval-records.jsonl}") String filePathConfigured) {
        this(mpcApprovalPolicy, auditLogService, null, null,
                storeTypeConfigured, useDatabaseLegacy, filePathConfigured,
                repositoryProvider != null ? repositoryProvider.getIfAvailable() : null);
    }

    /**
     * 完整构造函数（支持注入自定义存储和通知器）。
     *
     * <p>行为约定：未显式传入 customStore 时按内存模式初始化
     * （等价于 store-type=memory，保持既有调用方行为不变）。</p>
     *
     * @param mpcApprovalPolicy MPC 审批策略
     * @param auditLogService   审计日志服务
     * @param customStore       自定义审批存储（null 时自动选择内存模式）
     * @param customNotifier    自定义通知器（null 时使用日志通知器）
     */
    public SigningApprovalService(MpcApprovalPolicy mpcApprovalPolicy,
                                  AuditLogService auditLogService,
                                  ApprovalStore customStore,
                                  ApprovalNotifier customNotifier) {
        this(mpcApprovalPolicy, auditLogService, customStore, customNotifier, "", false,
                "data/approval-records.jsonl", null);
    }

    /**
     * 全参私有构造函数（所有公共构造器的最终委托目标）。
     *
     * <p>存储选择优先级：</p>
     * <ol>
     *   <li>{@code customStore} 非空：直接使用（测试 / 高级定制场景）</li>
     *   <li>{@code storeType} 显式配置：memory / file / jpa 三选一</li>
     *   <li>回落遗留开关 {@code useDatabaseLegacy}：true → jpa，false → memory</li>
     * </ol>
     *
     * <p>jpa 模式要求 {@code jpaRepository} 非空；缺失时<b>抛 {@link IllegalStateException}
     * 拒绝启动</b>（#32 由"WARN + 静默回退内存"改为 fail-closed——审批状态属资金安全关键路径，
     * 多副本下静默降级等于把状态切成三份互不可见的内存副本，服务还照样健康，问题只藏在日志里）。
     * 未知的 {@code storeType} 同样抛错，避免拼写错误降级成内存存储。</p>
     */
    private SigningApprovalService(MpcApprovalPolicy mpcApprovalPolicy,
                                   AuditLogService auditLogService,
                                   ApprovalStore customStore,
                                   ApprovalNotifier customNotifier,
                                   String storeType,
                                   boolean useDatabaseLegacy,
                                   String approvalDataFilePath,
                                   SigningApprovalRequestRepository jpaRepository) {
        this.mpcApprovalPolicy = Objects.requireNonNull(mpcApprovalPolicy, "mpcApprovalPolicy");
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService");
        this.storeType = storeType == null ? "" : storeType;
        this.useDatabase = useDatabaseLegacy;
        this.approvalDataFilePath = approvalDataFilePath == null || approvalDataFilePath.isBlank()
                ? "data/approval-records.jsonl" : approvalDataFilePath;

        // 审批存储选择（优先级见方法 javadoc）
        if (customStore != null) {
            this.requestStore = customStore;
            log.info("使用自定义审批存储: {}", customStore.getClass().getSimpleName());
        } else {
            String effectiveType = resolveStoreType(this.storeType, useDatabaseLegacy);
            switch (effectiveType) {
                case "jpa" -> {
                    if (jpaRepository == null) {
                        // fail-closed：这里若静默回退内存，prod replicaCount=3 就退化成
                        // "pod A 建的审批 pod B/C 看不见"，而服务仍健康启动、只在日志里留一句 WARN。
                        throw new IllegalStateException(
                                "审批存储要求 store-type=jpa（或遗留开关 use-database=true），但 "
                                        + "SigningApprovalRequestRepository 不可用——拒绝启动。请检查 datasource 配置与 "
                                        + "spring-boot-starter-data-jpa 依赖；确为单实例部署才显式设置 "
                                        + "nexus.approval.store-type=memory。");
                    }
                    this.requestStore = new JpaApprovalStore(jpaRepository);
                    log.info("使用数据库持久化审批存储（多实例共享）: table=signing_approval_request");
                }
                case "file" -> {
                    this.requestStore = new FileBasedApprovalStore(this.approvalDataFilePath);
                    log.info("使用文件持久化审批存储: path={}", this.approvalDataFilePath);
                }
                case "memory" -> {
                    this.requestStore = new MapApprovalStore(new ConcurrentHashMap<>());
                    log.info("使用内存审批存储（单实例部署）");
                }
                default -> throw new IllegalStateException("未知的审批存储类型 '" + effectiveType
                        + "'，可选值: memory / file / jpa（拼错不得静默降级为内存存储）");
            }
        }

        // 通知器选择
        this.approvalNotifier = customNotifier != null ? customNotifier : new LoggingApprovalNotifier();

        // 确保 @Value 字段有默认值（单元测试手动构造时 Spring 不注入）
        if (this.largeAmountThreshold == null) this.largeAmountThreshold = new BigDecimal("10000");
        if (this.ttlSeconds == 0) this.ttlSeconds = 3600;
        if (this.cleanupIntervalMs == 0) this.cleanupIntervalMs = 60000;
        if (this.cleanupRetentionSeconds == 0) this.cleanupRetentionSeconds = 3600;

        log.info("SigningApprovalService 初始化完成: storeType={}, useDatabase(legacy)={}, storeClass={}, notifierType={}, "
                + "cleanupIntervalMs={}, retentionSeconds={}",
                this.storeType.isBlank() ? "(unset)" : this.storeType, useDatabaseLegacy,
                requestStore.getClass().getSimpleName(),
                approvalNotifier.getClass().getSimpleName(),
                cleanupIntervalMs, cleanupRetentionSeconds);
    }

    /**
     * 审批人白名单的启动期可见性检查。
     *
     * <p>必须放在 {@code @PostConstruct}：{@code approverWhitelist} 是 {@code @Value}
     * 字段注入，构造器执行时它还是 {@code null}，构造期打印出来的"是否配置"恒为 false。</p>
     *
     * <p>未配置时是 fail-closed（#129）：所有决策被拒、大额签名的人工审批实际不可用。
     * 这必须是一条独立 WARN，否则运维会把一连串 403 误读成"权限配错了"，而不是
     * "这个功能没启用"。</p>
     */
    @PostConstruct
    void logApproverWhitelistVisibility() {
        int count = configuredApproverCount();
        if (count > 0) {
            // 只记数量，不回显地址（审批人身份属运营信息）
            log.info("审批人白名单已配置（{} 项）：大额签名人工审批可用", count);
        } else {
            log.warn("审批人白名单未配置（或没有任何有效项）"
                    + "（nexus.approval.approver-whitelist / NEX_APPROVAL_WHITELIST）："
                    + "所有 approve/reject 决策都会被拒绝，大额签名人工审批实际不可用（fail-closed，见 #129）");
        }
    }

    private int configuredApproverCount() {
        if (approverWhitelist == null || approverWhitelist.trim().isEmpty()) {
            return 0;
        }
        return (int) Arrays.stream(approverWhitelist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .count();
    }

    /**
     * 解析生效的存储类型。
     *
     * <p>显式 {@code store-type} 优先；为空时回落遗留开关语义：
     * {@code use-database=true} → jpa，否则 memory（默认行为不变）。</p>
     *
     * @param storeTypeConfigured 显式配置的存储类型（可能为空 / 空白）
     * @param legacyUseDatabase   遗留开关值
     * @return 生效类型：memory / file / jpa
     */
    static String resolveStoreType(String storeTypeConfigured, boolean legacyUseDatabase) {
        if (storeTypeConfigured != null && !storeTypeConfigured.isBlank()) {
            return storeTypeConfigured.trim().toLowerCase(java.util.Locale.ROOT);
        }
        return legacyUseDatabase ? "jpa" : "memory";
    }

    /**
     * 当前生效的审批存储实例（包级可见，仅供同包测试断言存储类型使用，
     * 非公共 API）。
     */
    ApprovalStore storeInUse() {
        return requestStore;
    }

    /**
     * 判断给定金额是否需要多签审批。
     *
     * @param amount   金额
     * @param currency 币种
     * @return true 表示需要审批；false 表示可直接签名
     */
    public boolean requiresApproval(BigDecimal amount, String currency) {
        return requiresApproval(amount, currency, null);
    }

    /**
     * 判断给定金额是否需要多签审批（带转出地址豁免）。
     *
     * <p>豁免语义（(b2) 止血方案）：{@code fromPubkey}（trim 后）显式列入
     * {@code nexus.approval.exempt-from-pubkeys} 时返回 false——该地址的转账已在
     * 业务层（wallet-service 多签）审批，signing 不再重复拦截。
     * 名单未配置时本参数不生效，与两参版本等价。</p>
     *
     * @param amount     金额
     * @param currency   币种
     * @param fromPubkey 转出公钥（可为 null；null 或名单未配置时与两参版本等价）
     * @return true 表示需要审批；false 表示可直接签名
     */
    public boolean requiresApproval(BigDecimal amount, String currency, String fromPubkey) {
        if (amount == null || largeAmountThreshold == null) {
            return false;
        }
        if (fromPubkey != null && exemptFromPubkeySet().contains(fromPubkey.trim())) {
            return false;
        }
        return amount.compareTo(largeAmountThreshold) >= 0;
    }

    private Set<String> exemptFromPubkeySet() {
        if (exemptFromPubkeys == null || exemptFromPubkeys.trim().isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(exemptFromPubkeys.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
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
        if (!requiresApproval(amount, currency, fromPubkey)) {
            return null;
        }
        int required = Math.max(minRequiredApprovers,
                mpcApprovalPolicy.getRequiredApprovers(amount, currency));
        SigningApprovalRequest request = SigningApprovalRequest.create(
                fromPubkey, toPubkeyHash, amount, currency,
                required, ttlSeconds, initiator);
        requestStore.put(request.getRequestId(), request);

        // 通知审批人
        try {
            approvalNotifier.notifyApprovalCreated(request);
        } catch (Exception e) {
            log.warn("审批通知失败（不影响审批流程）: requestId={}, error={}",
                    request.getRequestId(), e.getMessage());
        }

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
     * <p>生产入口是 {@code POST /api/v1/signing/approvals/{requestId}/decision}
     * （{@link org.nexus.signing.controller.SigningApprovalController}）。在此之前本方法与
     * {@link #reject} 在全部 main 源码里没有调用方、也没有任何 approve/reject REST 端点，
     * 后果是没有任何路径能把请求从 PENDING 推到 APPROVED，而 {@code /sign/approved} 的前置判据
     * 恰是 {@code status == APPROVED} ⇒ 大额签名在"审批通过后执行"这一步事实上不可达，
     * {@code approver-whitelist} 与多签 {@code requiredApprovers} 都还是空转的配置。</p>
     *
     * <p>这与 #32 修的是两个缺陷：#32 修的是"审批请求只存在创建它的那个副本内存里，
     * 别的副本 {@code getRequest(approvalId)} 查不到"；本节说的是"决策写入侧无入口"，
     * 由上面的端点解决，勿混为一谈。</p>
     *
     * @param requestId 审批请求 ID
     * @param sourceIp  来源 IP（用于审计）
     * @param comment   审批备注，可为 null；非空时进审计明细（不落审批请求本体——
     *                  那需要新列，属 #52-B 已否决的范围）
     * @return 更新后的审批请求；requestId 不存在时返回 null
     */
    public SigningApprovalRequest approve(String requestId, String sourceIp, String comment) {
        return updateRequest(requestId, currentApprover(), sourceIp, comment, true);
    }

    /**
     * 提交审批拒绝。
     *
     * @param requestId 审批请求 ID
     * @param sourceIp  来源 IP（用于审计）
     * @param comment   拒绝理由，可为 null；非空时进审计明细
     * @return 更新后的审批请求；requestId 不存在时返回 null
     */
    public SigningApprovalRequest reject(String requestId, String sourceIp, String comment) {
        return updateRequest(requestId, currentApprover(), sourceIp, comment, false);
    }

    /**
     * 当前认证主体标识（JWT {@code sub}）——审批人身份的唯一来源。
     *
     * <p><b>为什么不是入参</b>（协调板裁定
     * {@code docs/agent-board/20261017-2015-nex-adjudication-50-approver-identity.md} 第 2 条）：
     * 白名单是按<b>标识</b>比对的，只要决策方法还收一个 {@code actor} 参数，"请求体自带身份"
     * 就随时可能被下一个调用方重新引入；把它从签名里删掉，这条路径在类型层面就凑不出伪造身份。
     * 未认证（含 Spring Security 的 anonymous token）返回 {@code null}，
     * 由 {@link #approverDenialReason} 归入 {@code approver_identity_missing} 拒掉——
     * 缺身份是拒绝，不是放行。</p>
     *
     * <p>{@code @PreAuthorize} 已在端点上把角色闸门拦在前面，这里是第二道：
     * 角色过但主体不在白名单（例如服务间 token 的服务身份）同样被拒。</p>
     */
    private static String currentApprover() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal == null ? null : String.valueOf(principal);
    }

    /**
     * 当前调用方（{@link #currentApprover()}）能否下决策；{@code null} 表示允许。
     *
     * <p>端点用它把拒绝原因回给调用方（协调板 #50 裁定的前置条件之一：拒绝理由要出现在
     * API 响应体里，运维才知道是"没配"还是"不在名单"）。这个方法<b>不改变判定</b>——
     * 判定与审计仍在 {@link #updateRequest} 内，端点只是复述同一个函数的结论，
     * 因此不存在"绕过端点就能批"的第二道口子。</p>
     */
    public String currentApproverDenialReason() {
        return approverDenialReason(currentApprover());
    }

    /**
     * 判断给定标识能否下决策，返回拒绝原因；返回 {@code null} 表示允许。
     *
     * <p><b>fail-closed（2026-10-17 改掉原来的 fail-open）</b>：白名单未配置时旧实现返回
     * {@code true}（"不限制"），于是"谁能审批大额签名"这件事的默认答案是<b>任何调用方</b>。
     * 大额签名的审批是资金路径上的最后一道人工闸门，缺配置必须等于"没人能批"，
     * 而不是"谁都能批"。要启用审批必须显式配置
     * {@code nexus.approval.approver-whitelist}（prod 两套拓扑此前都未设置该变量，见 #112）。</p>
     *
     * <p>拒绝原因分成三种可区分的字符串，是为了让审计里"没配"与"不在名单"不被混成一条：
     * 前者要运维去补配置，后者要排查调用方身份。</p>
     *
     * <p>刻意保持 private：身份只能由本类从 {@link SecurityContextHolder} 取
     * （见 {@link #currentApprover()}），调用方无权提交审批人标识。</p>
     *
     * @param approverAddress 审批人标识（来自已认证主体，绝不允许由请求体自带）
     * @return {@code null} 表示允许；否则为可进审计的拒绝原因
     */
    private String approverDenialReason(String approverAddress) {
        if (approverWhitelist == null || approverWhitelist.trim().isEmpty()) {
            return "approver_whitelist_not_configured";
        }
        if (approverAddress == null || approverAddress.isBlank()) {
            return "approver_identity_missing";
        }
        Set<String> whitelist = Arrays.stream(approverWhitelist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return whitelist.contains(approverAddress.trim()) ? null : "approver_not_in_whitelist";
    }

    private SigningApprovalRequest updateRequest(String requestId, String actor,
                                                 String sourceIp, String comment, boolean approve) {
        String decision = approve ? "APPROVE" : "REJECT";
        if (requestId == null) {
            return null;
        }
        // 项2：审批人白名单校验。fail-closed——白名单没配就等于没人能批（旧实现是"没配就不限制"）。
        String denialReason = approverDenialReason(actor);
        if (denialReason != null) {
            log.warn("审批人被拒绝: requestId={}, actor={}, reason={}, decision={}",
                    requestId, actor, denialReason, decision);
            auditLogService.log(decisionEvent(AuditEvent.Outcome.DENIED, actor, sourceIp,
                            requestId, decision, comment)
                    .detail("denied_reason", denialReason)
                    .build());
            // 返回当前请求快照（不修改状态）；若请求不存在则返回 null
            return requestStore.get(requestId);
        }
        SigningApprovalRequest existing = requestStore.get(requestId);
        if (existing == null) {
            return null;
        }
        if (existing.getStatus() != SigningApprovalRequest.Status.PENDING) {
            log.warn("审批请求状态非 PENDING，忽略决策: requestId={}, status={}, actor={}",
                    requestId, existing.getStatus(), actor);
            return existing;
        }
        SigningApprovalRequest updated = approve
                ? existing.withApproval(actor)
                : existing.withRejection(actor);
        requestStore.save(requestId, updated);

        log.info("审批决策: requestId={}, actor={}, decision={}, newStatus={}",
                requestId, actor, decision, updated.getStatus());

        auditLogService.log(decisionEvent(
                        approve ? AuditEvent.Outcome.SUCCESS : AuditEvent.Outcome.DENIED,
                        actor, sourceIp, requestId, decision, comment)
                .detail("new_status", updated.getStatus().name())
                .detail("approvals_count", updated.getApprovals().size())
                .detail("required", updated.getRequiredApprovers())
                .build());

        return updated;
    }

    /**
     * 构造一条 APPROVAL_DECISION 审计事件的公共前缀（决策/备注/目标/来源 IP）。
     *
     * <p>{@code comment} 只在非空时写入：审计里"没填备注"与"填了空串"不必区分，
     * 但一条恒定出现的 {@code comment=null} 会把检索噪声带进审计库。</p>
     */
    private AuditEvent.Builder decisionEvent(AuditEvent.Outcome outcome, String actor,
                                             String sourceIp, String requestId, String decision,
                                             String comment) {
        AuditEvent.Builder builder = AuditEvent.builder(AuditEvent.Type.APPROVAL_DECISION, outcome, actor)
                .sourceIp(sourceIp)
                .target(requestId)
                .detail("decision", decision);
        if (comment != null && !comment.isBlank()) {
            builder.detail("comment", comment.trim());
        }
        return builder;
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
            requestStore.save(requestId, expired);
            return expired;
        }
        return req;
    }

    /**
     * 标记审批请求已执行（签名已广播）。
     *
     * <p>v2.27.0 P1-8 修复：支持从 {@code EXECUTING} 状态迁移到 {@code EXECUTED}
     * （正常流程），同时保留从 {@code APPROVED} 直接迁移的向后兼容路径。</p>
     *
     * @param requestId 审批请求 ID
     * @return 更新后的审批请求；requestId 不存在时返回 null
     */
    public SigningApprovalRequest markExecuted(String requestId) {
        if (requestId == null) {
            return null;
        }
        SigningApprovalRequest existing = requestStore.get(requestId);
        if (existing == null) {
            return null;
        }
        if (existing.getStatus() != SigningApprovalRequest.Status.APPROVED
                && existing.getStatus() != SigningApprovalRequest.Status.EXECUTING) {
            log.warn("审批请求状态非 APPROVED/EXECUTING，无法标记 EXECUTED: requestId={}, status={}",
                    requestId, existing.getStatus());
            return existing;
        }
        // CAS 迁移：优先 EXECUTING→EXECUTED（正常流程），失败再尝试
        // APPROVED→EXECUTED（向后兼容路径）。两步 CAS 保证与并发执行路径互斥。
        boolean transitioned = requestStore.compareAndTransition(requestId,
                SigningApprovalRequest.Status.EXECUTING, SigningApprovalRequest.Status.EXECUTED)
                || requestStore.compareAndTransition(requestId,
                        SigningApprovalRequest.Status.APPROVED, SigningApprovalRequest.Status.EXECUTED);
        if (!transitioned) {
            log.warn("标记 EXECUTED 失败（并发状态已变更）: requestId={}", requestId);
            return requestStore.get(requestId);
        }
        log.info("审批请求标记已执行: requestId={}", requestId);
        return requestStore.get(requestId);
    }

    /**
     * P1-8 修复（v2.27.0 修复，审计复核后加固）：以存储层原子 CAS 将审批请求从 APPROVED
     * 标记为 EXECUTING。
     *
     * <p>在执行签名前调用此方法，确保同一审批请求不会被并发重复执行（双重放款）。
     * 早期实现为 get→判状态→save 三步非原子操作，两个并发请求可同时通过检查；
     * 现改为委托 {@link ApprovalStore#compareAndTransition} 的原子实现
     * （内存 compute / 文件 compute+落盘 / JPA 乐观锁），由存储层保证互斥。</p>
     *
     * @param requestId 审批请求 ID
     * @return true 表示成功迁移到 EXECUTING；false 表示状态不匹配或并发冲突
     */
    public boolean tryMarkExecuting(String requestId) {
        if (requestId == null) {
            return false;
        }
        boolean transitioned = requestStore.compareAndTransition(requestId,
                SigningApprovalRequest.Status.APPROVED, SigningApprovalRequest.Status.EXECUTING);
        if (!transitioned) {
            SigningApprovalRequest current = requestStore.get(requestId);
            log.warn("CAS 失败：审批请求状态非 APPROVED 或已被并发迁移: requestId={}, status={}",
                    requestId, current == null ? "不存在" : current.getStatus());
            return false;
        }
        log.info("审批请求标记执行中 (CAS APPROVED→EXECUTING): requestId={}", requestId);
        return true;
    }

    /**
     * P1-8 修复（v2.27.0 修复，审计复核后加固）：以存储层原子 CAS 将审批请求从 EXECUTING
     * 回退到 APPROVED（签名执行失败时调用）。
     *
     * <p>当签名执行失败后，调用此方法将审批请求恢复到 APPROVED 状态，
     * 允许后续重试。若当前状态不是 EXECUTING（含并发下已被迁移），则不执行任何操作。</p>
     *
     * @param requestId 审批请求 ID
     */
    public void revertExecuting(String requestId) {
        if (requestId == null) {
            return;
        }
        boolean reverted = requestStore.compareAndTransition(requestId,
                SigningApprovalRequest.Status.EXECUTING, SigningApprovalRequest.Status.APPROVED);
        if (reverted) {
            log.info("审批请求回退 (EXECUTING→APPROVED): requestId={}", requestId);
        } else {
            SigningApprovalRequest current = requestStore.get(requestId);
            log.warn("无法回退：审批请求状态非 EXECUTING 或已被并发迁移: requestId={}, status={}",
                    requestId, current == null ? "不存在" : current.getStatus());
        }
    }

    /**
     * 定时清理过期审批请求（项1）。
     *
     * <p>由 {@code @Scheduled} 驱动，每 {@code nexus.approval.cleanup-interval-ms} 毫秒执行一次（默认 60 秒）。
     * 清理分两阶段，避免直接删除 PENDING 请求导致调用方查询返回 null 而非 EXPIRED：</p>
     * <ol>
     *   <li><b>标记过期</b>：扫描 PENDING 且 {@code deadline < now} 的请求，以存储层原子 CAS
     *       迁移为 EXPIRED 并记录审计日志。保留请求实例供调用方查询与审计追溯。</li>
     *   <li><b>EXECUTING 卡死恢复</b>（P1-8 修复，v2.27.0）：扫描 EXECUTING 且
     *       {@code deadline < now} 的请求（签名服务崩溃导致卡死），以原子 CAS 回退到 APPROVED
     *       允许重试并记录审计日志——与 {@link #revertExecuting} 同一口径。</li>
     *   <li><b>释放内存</b>：清理终态（EXPIRED / REJECTED / EXECUTED）且
     *       {@code deadline < now - cleanupRetentionSeconds} 的请求，从 {@link #requestStore} 移除。
     *       保留期内（默认 1 小时）的终态请求保留以供审计查询。
     *       APPROVED 状态不清理（签名执行流程可能仍在进行，由 {@link #markExecuted} 处理）。</li>
     * </ol>
     *
     * <p>并发口径：两处状态迁移都委托 {@link ApprovalStore#compareAndTransition}，迁移失败
     * （状态已变或其他副本抢先）即跳过该条、不覆写也不重复出审计。这是把 {@code store-type}
     * 切到共享实现（{@link JpaApprovalStore}，prod {@code replicaCount=3} 共表）的<b>前置条件</b>：
     * 整行 {@code save} 覆写在共享 store 上会把其他副本刚写入的状态打回旧值，包括把
     * {@code EXECUTED}（已签名）回退成 {@code APPROVED} 从而重开二次签名窗口。</p>
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
                // 原子 CAS 而非裸 save：store 被多副本共享（JpaApprovalStore，prod replicaCount=3）时，
                // 整行覆写会把别副本刚迁移的状态打回旧值（丢更新），同一请求还会各写一条审计。
                if (!requestStore.compareAndTransition(entry.getKey(),
                        SigningApprovalRequest.Status.PENDING, SigningApprovalRequest.Status.EXPIRED)) {
                    log.debug("审批请求过期标记由其他副本先行完成: requestId={}", entry.getKey());
                    continue;
                }
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

        // P1-8 修复（v2.27.0）：EXECUTING 卡死恢复——签名服务崩溃后 EXECUTING 状态的请求
        // 超过 deadline 仍未完成，回退到 APPROVED 允许后续重试。
        // 与 revertExecuting 同口径走 CAS：共享 store 下若此处用裸 save，可能把别副本刚写的
        // EXECUTED（已签名）打回 APPROVED ⇒ 同一笔资金二次签名的窗口。
        int revertedExecuting = 0;
        for (Map.Entry<String, SigningApprovalRequest> entry : requestStore.entrySet()) {
            SigningApprovalRequest req = entry.getValue();
            if (req.getStatus() == SigningApprovalRequest.Status.EXECUTING
                    && req.getDeadline().isBefore(now)) {
                if (!requestStore.compareAndTransition(entry.getKey(),
                        SigningApprovalRequest.Status.EXECUTING, SigningApprovalRequest.Status.APPROVED)) {
                    log.debug("EXECUTING 卡死恢复由其他副本先行完成或状态已迁移: requestId={}",
                            entry.getKey());
                    continue;
                }
                revertedExecuting++;
                log.warn("EXECUTING 请求卡死恢复 (EXECUTING→APPROVED): requestId={}, deadline={}",
                        entry.getKey(), req.getDeadline());
                auditLogService.log(AuditEvent.builder(AuditEvent.Type.APPROVAL_REQUEST,
                                AuditEvent.Outcome.FAILURE, req.getInitiator())
                        .target(entry.getKey())
                        .detail("reason", "executing_stuck_recovery")
                        .detail("new_status", "APPROVED")
                        .build());
            }
        }

        // 阶段2：清理终态且超过保留期的请求
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
                requestStore.remove(entry.getKey());
                removed++;
            }
        }

        if (markedExpired > 0 || revertedExecuting > 0 || removed > 0) {
            log.info("审批请求清理完成: markedExpired={}, revertedExecuting={}, removedTerminal={}, remaining={}",
                    markedExpired, revertedExecuting, removed, requestStore.size());
        }
    }

    /**
     * 获取所有审批请求（运维查询用）。
     *
     * @return 不可修改的审批请求快照（按 requestId 索引）
     */
    public Map<String, SigningApprovalRequest> getPendingRequests() {
        Map<String, SigningApprovalRequest> snapshot = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, SigningApprovalRequest> entry : requestStore.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(snapshot);
    }
}