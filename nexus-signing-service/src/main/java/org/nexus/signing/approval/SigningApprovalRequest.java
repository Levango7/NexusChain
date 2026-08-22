package org.nexus.signing.approval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 多签审批请求（P2-F1 完整安全架构）。
 *
 * <p>对大额签名操作要求多签审批：发起签名请求时创建审批请求，
 * 收集足够审批签名后才能执行实际签名。本类为不可变值对象，
 * 审批状态变更通过 {@link #withApproval} / {@link #withStatus} 返回新实例。</p>
 *
 * <h3>状态机</h3>
 * <pre>
 *   PENDING ──(收集到足够审批)──→ APPROVED ──(执行签名)──→ EXECUTED
 *       │                              │
 *       └──(任一审批人拒绝)──→ REJECTED │
 *                                     │
 *       ──(超时未收集足够审批)──→ EXPIRED
 * </pre>
 *
 * <h3>与 MpcApprovalPolicy 的关系</h3>
 * <p>本类复用 {@link org.nexus.signing.mpc.MpcApprovalPolicy#getRequiredApprovers}
 * 计算所需审批人数，但不直接耦合 MPC 阈值签名流程——审批通过后是否走 MPC
 * 多签由 {@link org.nexus.signing.mpc.MpcApprovalPolicy#isColdWalletTier}
 * 决定，本类仅负责审批门控。</p>
 */
public final class SigningApprovalRequest {

    /** 审批请求状态。 */
    public enum Status {
        /** 待审批：已创建，等待审批人决策。 */
        PENDING,
        /** 已批准：收集到足够审批，可执行签名。 */
        APPROVED,
        /** 已拒绝：任一审批人拒绝。 */
        REJECTED,
        /** 执行中：签名正在广播（P1-8 修复，v2.27.0）。CAS 中间态，防止并发重复执行。 */
        EXECUTING,
        /** 已执行：签名已广播。 */
        EXECUTED,
        /** 已过期：超时未收集足够审批。 */
        EXPIRED
    }

    private final String requestId;
    private final String fromPubkey;
    private final String toPubkeyHash;
    private final BigDecimal amount;
    private final String currency;
    private final int requiredApprovers;
    private final Instant createdAt;
    private final Instant deadline;
    private final Status status;
    private final Set<String> approvals;
    private final Set<String> rejections;
    private final String initiator;

    SigningApprovalRequest(String requestId, String fromPubkey, String toPubkeyHash,
                           BigDecimal amount, String currency, int requiredApprovers,
                           Instant createdAt, Instant deadline, Status status,
                           Set<String> approvals, Set<String> rejections,
                           String initiator) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.fromPubkey = Objects.requireNonNull(fromPubkey, "fromPubkey");
        this.toPubkeyHash = Objects.requireNonNull(toPubkeyHash, "toPubkeyHash");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.currency = currency == null ? "USDT" : currency;
        if (requiredApprovers < 1) {
            throw new IllegalArgumentException("requiredApprovers must be >= 1, got " + requiredApprovers);
        }
        this.requiredApprovers = requiredApprovers;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.deadline = Objects.requireNonNull(deadline, "deadline");
        this.status = Objects.requireNonNull(status, "status");
        this.approvals = Collections.unmodifiableSet(new LinkedHashSet<>(approvals));
        this.rejections = Collections.unmodifiableSet(new LinkedHashSet<>(rejections));
        this.initiator = Objects.requireNonNull(initiator, "initiator");
    }

    /**
     * 创建新的审批请求工厂方法。
     *
     * @param fromPubkey       转出公钥
     * @param toPubkeyHash     转入公钥 hash
     * @param amount           金额
     * @param currency         币种
     * @param requiredApprovers 所需审批人数
     * @param ttlSeconds       审批有效期（秒）
     * @param initiator        发起人标识（JWT subject）
     * @return 新的 PENDING 审批请求
     */
    public static SigningApprovalRequest create(String fromPubkey, String toPubkeyHash,
                                                BigDecimal amount, String currency,
                                                int requiredApprovers,
                                                long ttlSeconds, String initiator) {
        Instant now = Instant.now();
        return new SigningApprovalRequest(
                UUID.randomUUID().toString(),
                fromPubkey, toPubkeyHash, amount, currency,
                requiredApprovers,
                now, now.plusSeconds(ttlSeconds),
                Status.PENDING,
                Collections.emptySet(), Collections.emptySet(),
                initiator);
    }

    /**
     * 添加一个审批通过，返回新的请求实例。
     *
     * <p>若审批人数达到 {@code requiredApprovers}，新实例状态变为 {@link Status#APPROVED}。</p>
     *
     * <p><b>低8 approver 白名单校验</b>：本方法为不可变值对象的纯函数式变更操作，
     * 不执行业务级白名单校验（白名单校验需要访问配置，与值对象职责不符）。
     * 审批人白名单校验在 {@link org.nexus.signing.approval.SigningApprovalService#updateRequest}
     * 中完成（v2.2.2 已实现，调用 {@code isApproverAllowed} 校验
     * {@code nexus.approval.approver-whitelist}），不在白名单的审批人决策被拒绝并记录审计日志，
     * 不会调用本方法。本方法仅做基础防御：{@code null} 或空白 approver 视为无效输入，
     * 返回当前实例（不修改状态），保证值对象的健壮性。</p>
     *
     * @param approver 审批人标识
     * @return 新的请求实例；approver 为 null 或空白时返回当前实例（不修改状态）
     */
    public SigningApprovalRequest withApproval(String approver) {
        // 基础防御：null / 空白 approver 视为无效输入，返回当前实例。
        // 业务级白名单校验由 SigningApprovalService.updateRequest 在调用本方法前完成。
        if (approver == null || approver.isBlank()) {
            return this;
        }
        Set<String> newApprovals = new LinkedHashSet<>(this.approvals);
        newApprovals.add(approver);
        Status newStatus = this.status;
        if (newStatus == Status.PENDING && newApprovals.size() >= requiredApprovers) {
            newStatus = Status.APPROVED;
        }
        return new SigningApprovalRequest(
                requestId, fromPubkey, toPubkeyHash, amount, currency,
                requiredApprovers, createdAt, deadline, newStatus,
                newApprovals, rejections, initiator);
    }

    /**
     * 添加一个审批拒绝，返回新的请求实例。
     *
     * <p>任一审批人拒绝即整体拒绝，新实例状态变为 {@link Status#REJECTED}。</p>
     *
     * @param rejecter 拒绝人标识
     * @return 新的请求实例
     */
    public SigningApprovalRequest withRejection(String rejecter) {
        if (rejecter == null || rejecter.isBlank()) {
            return this;
        }
        Set<String> newRejections = new LinkedHashSet<>(this.rejections);
        newRejections.add(rejecter);
        Status newStatus = this.status == Status.PENDING ? Status.REJECTED : this.status;
        return new SigningApprovalRequest(
                requestId, fromPubkey, toPubkeyHash, amount, currency,
                requiredApprovers, createdAt, deadline, newStatus,
                approvals, newRejections, initiator);
    }

    /**
     * 变更状态（用于标记 EXECUTED / EXPIRED）。
     *
     * @param newStatus 新状态
     * @return 新的请求实例
     */
    public SigningApprovalRequest withStatus(Status newStatus) {
        return new SigningApprovalRequest(
                requestId, fromPubkey, toPubkeyHash, amount, currency,
                requiredApprovers, createdAt, deadline, newStatus,
                approvals, rejections, initiator);
    }

    /**
     * 判断审批请求是否已过期（当前时间超过 deadline 且状态仍为 PENDING）。
     */
    public boolean isExpired() {
        return status == Status.PENDING && Instant.now().isAfter(deadline);
    }

    /**
     * 判断签名操作是否可执行（状态为 APPROVED）。
     */
    public boolean isSignable() {
        return status == Status.APPROVED;
    }

    public String getRequestId() { return requestId; }
    public String getFromPubkey() { return fromPubkey; }
    public String getToPubkeyHash() { return toPubkeyHash; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public int getRequiredApprovers() { return requiredApprovers; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDeadline() { return deadline; }
    public Status getStatus() { return status; }
    public Set<String> getApprovals() { return approvals; }
    public Set<String> getRejections() { return rejections; }
    public String getInitiator() { return initiator; }

    @Override
    public String toString() {
        return "SigningApprovalRequest{requestId=" + requestId
                + ", amount=" + amount + " " + currency
                + ", required=" + requiredApprovers
                + ", status=" + status
                + ", approvals=" + approvals.size()
                + ", rejections=" + rejections.size()
                + '}';
    }
}