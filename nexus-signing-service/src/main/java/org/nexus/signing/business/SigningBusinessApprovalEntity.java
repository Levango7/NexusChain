package org.nexus.signing.business;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A2 业务批准凭据登记行（{@code signing_business_approval} 表，V5）。
 *
 * <p>语义：<b>已经</b>批准、待消费的一次性凭据——与
 * {@code org.nexus.signing.approval.SigningApprovalRequestEntity} 的
 * "等待签名人批准"状态机（PENDING/EXECUTING/TTL 回退）刻意分离：
 * 本表只有 REGISTERED → CONSUMED 一个单向迁移（见
 * {@link SigningBusinessApprovalRepository#consumeIfRegistered} 的条件更新）。</p>
 *
 * <p><b>并发控制说明</b>：{@code version} 列仅为与 V1 风格对齐而保留，
 * <b>未标注 {@code @Version}</b>——本表唯一的并发路径是"两个并发 signTransfer
 * 消费同一凭据"，由仓储的条件 UPDATE（{@code WHERE state='REGISTERED'}）原子仲裁，
 * 不依赖 JPA 乐观锁。登记侧幂等靠唯一索引 {@code uk_business_approval_id}。</p>
 */
@Entity
@Table(name = "signing_business_approval")
public class SigningBusinessApprovalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务单号（wallet 提现 requestId），全局唯一。 */
    @Column(name = "business_approval_id", unique = true, nullable = false, length = 64)
    private String businessApprovalId;

    /** canonical 载荷摘要（小写 64 位 hex，双侧共享 BusinessApprovalPayloads 算法）。 */
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    /** 登记金额（审计展示；校验一律走 payloadHash 比对）。 */
    @Column(name = "amount", nullable = false, precision = 36, scale = 18)
    private BigDecimal amount;

    /** 币种（元数据；signTransfer 契约当前 USDT 固定）。 */
    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    /** 转出公钥（审计展示）。 */
    @Column(name = "from_pubkey", nullable = false, length = 512)
    private String fromPubkey;

    /** 转入公钥 hash（审计展示）。 */
    @Column(name = "to_pubkey_hash", nullable = false, length = 128)
    private String toPubkeyHash;

    /** 审批人快照（JSON 数组文本，审计展示，不参与校验）。 */
    @Column(name = "approvers_json")
    private String approversJson;

    /** REGISTERED / CONSUMED（单向迁移）。 */
    @Column(name = "state", nullable = false, length = 16)
    private String state = "REGISTERED";

    /** 消费时间（CAS 成功时写入）。 */
    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    /** 凭据失效时间（过期不豁免、不续期）。 */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** 登记时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 保留列（见类 javadoc 并发说明）。 */
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getBusinessApprovalId() { return businessApprovalId; }
    public void setBusinessApprovalId(String businessApprovalId) { this.businessApprovalId = businessApprovalId; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getFromPubkey() { return fromPubkey; }
    public void setFromPubkey(String fromPubkey) { this.fromPubkey = fromPubkey; }
    public String getToPubkeyHash() { return toPubkeyHash; }
    public void setToPubkeyHash(String toPubkeyHash) { this.toPubkeyHash = toPubkeyHash; }
    public String getApproversJson() { return approversJson; }
    public void setApproversJson(String approversJson) { this.approversJson = approversJson; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public LocalDateTime getConsumedAt() { return consumedAt; }
    public void setConsumedAt(LocalDateTime consumedAt) { this.consumedAt = consumedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
