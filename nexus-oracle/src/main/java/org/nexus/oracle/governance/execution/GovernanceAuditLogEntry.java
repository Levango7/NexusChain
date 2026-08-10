package org.nexus.oracle.governance.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Temporal;
import jakarta.persistence.TemporalType;

import java.time.Instant;
import java.util.Date;

/**
 * 治理审计日志持久化实体（GOV-P0-02）。
 *
 * <p>每条记录描述一次治理执行的完整审计轨迹，并通过哈希链（{@link #previousHash} + {@link #entryHash}）
 * 实现防篡改：任何对历史记录的修改都会破坏链式结构，可被 {@link GovernanceAuditLog#verifyAuditChain(String)} 检测。
 *
 * <p>实体字段：
 * <ul>
 *   <li>{@code id} — 自增主键</li>
 *   <li>{@code proposalId} — 提案 ID（建索引，便于按提案查询）</li>
 *   <li>{@code action} — 执行动作（如 SOFTWARE_UPGRADE / TREASURY_SPEND）</li>
 *   <li>{@code timestamp} — 记录时间</li>
 *   <li>{@code executor} — 操作人（提案发起者）</li>
 *   <li>{@code result} — 执行结果（success / failure + 详情 JSON）</li>
 *   <li>{@code previousHash} — 前一条记录的 entryHash（首条为 "0" * 64）</li>
 *   <li>{@code entryHash} — 本条记录的 SHA-256 哈希</li>
 * </ul>
 *
 * <p>哈希计算公式：
 * <pre>{@code
 * entryHash = SHA-256(proposalId + action + timestamp + executor + result + previousHash)
 * }</pre>
 *
 * @since 2.1.0
 */
@Entity
@Table(name = "governance_audit_log", indexes = {
        @Index(name = "idx_audit_proposal_id", columnList = "proposal_id"),
        @Index(name = "idx_audit_timestamp", columnList = "timestamp")
})
public class GovernanceAuditLogEntry {

    /** 自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 提案 ID */
    @Column(name = "proposal_id", nullable = false, length = 128)
    private String proposalId;

    /** 执行动作（提案类型字符串） */
    @Column(name = "action", nullable = false, length = 64)
    private String action;

    /** 记录时间 */
    @Temporal(TemporalType.TIMESTAMP)
    @Column(name = "timestamp", nullable = false)
    private Date timestamp;

    /** 操作人（提案发起者） */
    @Column(name = "executor", nullable = false, length = 128)
    private String executor;

    /** 执行结果（JSON 字符串：包含 success、previousState、newState、details） */
    @Lob
    @Column(name = "result", nullable = false)
    private String result;

    /** 前一条记录的 entryHash（64 hex 字符，首条为 "0" * 64） */
    @Column(name = "previous_hash", nullable = false, length = 64)
    private String previousHash;

    /** 本条记录的 SHA-256 哈希（64 hex 字符） */
    @Column(name = "entry_hash", nullable = false, length = 64)
    private String entryHash;

    /**
     * 默认构造函数（JPA 要求）。
     */
    public GovernanceAuditLogEntry() {
    }

    /**
     * 全参构造函数。
     *
     * @param id           主键
     * @param proposalId   提案 ID
     * @param action       执行动作
     * @param timestamp    记录时间
     * @param executor     操作人
     * @param result       执行结果 JSON
     * @param previousHash 前一条记录哈希
     * @param entryHash    本条记录哈希
     */
    public GovernanceAuditLogEntry(Long id, String proposalId, String action, Date timestamp,
                                   String executor, String result, String previousHash, String entryHash) {
        this.id = id;
        this.proposalId = proposalId;
        this.action = action;
        this.timestamp = timestamp;
        this.executor = executor;
        this.result = result;
        this.previousHash = previousHash;
        this.entryHash = entryHash;
    }

    /** @return 自增主键 */
    public Long getId() {
        return id;
    }

    /** @param id 自增主键 */
    public void setId(Long id) {
        this.id = id;
    }

    /** @return 提案 ID */
    public String getProposalId() {
        return proposalId;
    }

    /** @param proposalId 提案 ID */
    public void setProposalId(String proposalId) {
        this.proposalId = proposalId;
    }

    /** @return 执行动作 */
    public String getAction() {
        return action;
    }

    /** @param action 执行动作 */
    public void setAction(String action) {
        this.action = action;
    }

    /** @return 记录时间（{@link Date} 兼容 JPA） */
    public Date getTimestamp() {
        return timestamp;
    }

    /** @param timestamp 记录时间 */
    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
    }

    /** @return 操作人 */
    public String getExecutor() {
        return executor;
    }

    /** @param executor 操作人 */
    public void setExecutor(String executor) {
        this.executor = executor;
    }

    /** @return 执行结果 JSON */
    public String getResult() {
        return result;
    }

    /** @param result 执行结果 JSON */
    public void setResult(String result) {
        this.result = result;
    }

    /** @return 前一条记录哈希 */
    public String getPreviousHash() {
        return previousHash;
    }

    /** @param previousHash 前一条记录哈希 */
    public void setPreviousHash(String previousHash) {
        this.previousHash = previousHash;
    }

    /** @return 本条记录哈希 */
    public String getEntryHash() {
        return entryHash;
    }

    /** @param entryHash 本条记录哈希 */
    public void setEntryHash(String entryHash) {
        this.entryHash = entryHash;
    }

    @Override
    public String toString() {
        return "GovernanceAuditLogEntry{id=" + id
                + ", proposalId='" + proposalId + '\''
                + ", action='" + action + '\''
                + ", timestamp=" + timestamp
                + ", executor='" + executor + '\''
                + ", previousHash='" + previousHash + '\''
                + ", entryHash='" + entryHash + '\'' + '}';
    }
}