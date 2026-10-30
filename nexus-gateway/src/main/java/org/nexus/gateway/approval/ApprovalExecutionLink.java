package org.nexus.gateway.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 大额签名审批与业务请求的通用链接（gateway 侧，见 V109 迁移）。
 *
 * <p>signing-service 对超过阈值的转账返回 {@code statusCode=2001 + approvalId}，
 * 不隐式动钱；本表记录"哪笔业务请求对应哪个审批请求、执行到哪一步"，
 * 由 {@link SigningApprovalExecutionService} 在审批通过后调用
 * {@code /transfers/sign/approved} 完成签名/广播。</p>
 *
 * <p><b>资金安全不变量</b>：</p>
 * <ul>
 *   <li>只有 {@link Status#APPROVED} 且<b>从未发起过执行</b>（{@code executionAttemptedAt == null}）
 *       或最近一次执行得到明确"未动账"答复（5000，状态回退到 APPROVED）时可以执行；</li>
 *   <li>一旦发起过执行但结果不明（超时/降级/5001），状态停在 EXECUTING 或转为
 *       EXECUTION_UNKNOWN，<b>永不自动重试</b>——签名服务会把超时的 EXECUTING 回退成
 *       APPROVED，自动重试存在二次打款窗口；</li>
 *   <li>首腿（直接签名）结果不明同样落一行终态 EXECUTION_UNKNOWN（approvalId 为空），
 *       用唯一键堵住同一 requestKey 的再次发起——见 {@link SigningApprovalExecutionService}
 *       首腿词表；**确定未动账**（失败码 5000/4000/4030）不落行，保持可安全重试；</li>
 *   <li>{@code txHash} 只在拿到确定哈希时写入，任何路径都不清空。</li>
 * </ul>
 */
@Entity
@Table(name = "approval_execution_link")
public class ApprovalExecutionLink {

    /** 调用方视角的执行状态。 */
    public enum Status {
        /** 待审批：signing-service 已受理，等待人工决策 */
        AWAITING_APPROVAL,
        /** 已批准，尚未发起执行或最近一次执行明确失败（未动账），可安全执行 */
        APPROVED,
        /** 已发起执行但结果未确认（含并发被抢 5001、超时、服务降级）——不得再次执行 */
        EXECUTING,
        /** 执行成功且拿到交易哈希 */
        EXECUTED,
        /** 签名服务报告已执行，但调用方没有交易哈希——必须人工核对 */
        EXECUTED_UNVERIFIED,
        /** 执行结果不可判定（如签名状态回退/长时间卡在 EXECUTING）——必须人工核对 */
        EXECUTION_UNKNOWN,
        /** 确定失败（审批被拒/过期/等待超时；确定未动账） */
        FAILED;

        /** 是否为终态（不再由自动扫描推进）。 */
        public boolean isTerminal() {
            return this == EXECUTED || this == EXECUTED_UNVERIFIED
                    || this == EXECUTION_UNKNOWN || this == FAILED;
        }

        /** 是否为"签名已发出/结果未知"类状态（人工核对，禁止自动重试）。 */
        public boolean requiresManualVerification() {
            return this == EXECUTED_UNVERIFIED || this == EXECUTION_UNKNOWN;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务请求标识（唯一），如 {@code execution:REFUND:refund:42}。 */
    @Column(name = "request_key", unique = true, nullable = false, length = 160)
    private String requestKey;

    /** 业务类型（PAYMENT / REFUND / SETTLEMENT / WITHDRAWAL / SWEEP 等）。 */
    @Column(name = "request_type", nullable = false, length = 32)
    private String requestType;

    /**
     * signing-service 的审批请求 ID。
     *
     * <p><b>可为 null</b>（V110）：首腿（直接签名）结果不明时落一行终态
     * {@link Status#EXECUTION_UNKNOWN} 链接，仅作为同一 requestKey 的阻塞闸口，
     * 不对应任何审批请求。此类行一律为终态，任何轮询都不会据此查询审批状态。</p>
     */
    @Column(name = "approval_id", nullable = true, length = 64)
    private String approvalId;

    @Column(name = "from_pubkey", length = 512)
    private String fromPubkey;

    @Column(name = "to_pubkey_hash", length = 128)
    private String toPubkeyHash;

    @Column(name = "amount", precision = 36, scale = 18)
    private BigDecimal amount;

    @Column(name = "currency", length = 16)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private Status status;

    /** 链上交易哈希；拿到后永不清空。 */
    @Column(name = "tx_hash", length = 128)
    private String txHash;

    /** 最近一次异常/拒绝原因（排障用，最长 512）。 */
    @Column(name = "last_error", length = 512)
    private String lastError;

    /** 状态轮询/执行尝试次数。 */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * 最近一次发起执行的时间。
     *
     * <p>非空即表示"已经把执行请求发出去过"。即便状态因 5000 回退到 APPROVED 时清空，
     * 也只在签名服务<b>明确答复未动账</b>的前提下清空。</p>
     */
    @Column(name = "execution_attempted_at")
    private LocalDateTime executionAttemptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    /** 乐观锁：多副本并发推进同一链接时，后提交者失败而不是静默覆盖。 */
    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestKey() { return requestKey; }
    public void setRequestKey(String requestKey) { this.requestKey = requestKey; }

    /**
     * 从 {@code execution:<类型>:<业务 ID>} 形态的 requestKey 中取出业务标识。
     *
     * <p>推进任务据此回写业务记录（如订阅的 authTxHash）。业务 ID 允许自带冒号
     * （如 {@code SUB_NO:3}），因此只剥掉前两段。</p>
     *
     * @return 业务标识；key 形态不符时返回原 key
     */
    public String businessId() {
        if (requestKey == null) {
            return null;
        }
        int first = requestKey.indexOf(':');
        int second = first < 0 ? -1 : requestKey.indexOf(':', first + 1);
        return second < 0 ? requestKey : requestKey.substring(second + 1);
    }

    public String getRequestType() { return requestType; }
    public void setRequestType(String requestType) { this.requestType = requestType; }

    public String getApprovalId() { return approvalId; }
    public void setApprovalId(String approvalId) { this.approvalId = approvalId; }

    public String getFromPubkey() { return fromPubkey; }
    public void setFromPubkey(String fromPubkey) { this.fromPubkey = fromPubkey; }

    public String getToPubkeyHash() { return toPubkeyHash; }
    public void setToPubkeyHash(String toPubkeyHash) { this.toPubkeyHash = toPubkeyHash; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public LocalDateTime getExecutionAttemptedAt() { return executionAttemptedAt; }
    public void setExecutionAttemptedAt(LocalDateTime executionAttemptedAt) {
        this.executionAttemptedAt = executionAttemptedAt;
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
