package org.nexus.signing.approval;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 多签审批请求 Entity，映射 {@code signing_approval_request} 表（任务 #375）。
 *
 * <p>替代 {@link SigningApprovalService} 原进程内
 * {@code ConcurrentHashMap<String, SigningApprovalRequest>} 内存存储，
 * 消除多实例部署下审批状态不共享的风险。</p>
 *
 * <p>领域对象 {@link SigningApprovalRequest} 为不可变值对象（状态变更返回新实例），
 * 本 Entity 仅作行级持久化载体：每次 {@code save} 整体覆写行记录，
 approvals / rejections 集合由 {@link JpaApprovalStore} 以 JSON 文本序列化到
 * {@code approvals_json} / {@code rejections_json} 列。</p>
 *
 * <p>时间字段沿用 nexus-wallet-service 范式使用 {@link LocalDateTime}（UTC 语义），
 * 与领域对象的 {@code Instant} 在 {@link JpaApprovalStore} 中互转。
 * {@link Version} 乐观锁为多实例并发提供保护：两个实例同时 CAS
 * APPROVED→EXECUTING 时，后提交者抛出乐观锁异常而非静默丢更新。</p>
 */
@Entity
@Table(name = "signing_approval_request")
public class SigningApprovalRequestEntity {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务请求 ID（UUID），唯一约束 {@code uk_request_id}。 */
    @Column(name = "request_id", unique = true, nullable = false, length = 64)
    private String requestId;

    /** 转出公钥（hex）。 */
    @Column(name = "from_pubkey", nullable = false, length = 512)
    private String fromPubkey;

    /** 转入公钥 hash。 */
    @Column(name = "to_pubkey_hash", nullable = false, length = 128)
    private String toPubkeyHash;

    /** 审批金额，36 位总精度 / 18 位小数。 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 18)
    private BigDecimal amount;

    /** 币种（默认 USDT）。 */
    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    /** 所需审批人数。 */
    @Column(name = "required_approvers", nullable = false)
    private Integer requiredApprovers;

    /** 审批状态，字符串持久化，保证数据库可读性与枚举类型安全。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SigningApprovalRequest.Status status;

    /** 已批准审批人集合（JSON 数组文本），空集时存 NULL。 */
    @Column(name = "approvals_json")
    private String approvalsJson;

    /** 已拒绝审批人集合（JSON 数组文本），空集时存 NULL。 */
    @Column(name = "rejections_json")
    private String rejectionsJson;

    /** 发起人标识（JWT subject）。 */
    @Column(name = "initiator", nullable = false, length = 128)
    private String initiator;

    /** 创建时间（UTC 语义）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 审批截止时间（UTC 语义）。 */
    @Column(name = "deadline", nullable = false)
    private LocalDateTime deadline;

    /** 乐观锁版本号，多实例并发写入保护。 */
    @Version
    @Column(name = "version")
    private Long version;

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getFromPubkey() { return fromPubkey; }
    public void setFromPubkey(String fromPubkey) { this.fromPubkey = fromPubkey; }

    public String getToPubkeyHash() { return toPubkeyHash; }
    public void setToPubkeyHash(String toPubkeyHash) { this.toPubkeyHash = toPubkeyHash; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Integer getRequiredApprovers() { return requiredApprovers; }
    public void setRequiredApprovers(Integer requiredApprovers) { this.requiredApprovers = requiredApprovers; }

    public SigningApprovalRequest.Status getStatus() { return status; }
    public void setStatus(SigningApprovalRequest.Status status) { this.status = status; }

    public String getApprovalsJson() { return approvalsJson; }
    public void setApprovalsJson(String approvalsJson) { this.approvalsJson = approvalsJson; }

    public String getRejectionsJson() { return rejectionsJson; }
    public void setRejectionsJson(String rejectionsJson) { this.rejectionsJson = rejectionsJson; }

    public String getInitiator() { return initiator; }
    public void setInitiator(String initiator) { this.initiator = initiator; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getDeadline() { return deadline; }
    public void setDeadline(LocalDateTime deadline) { this.deadline = deadline; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}