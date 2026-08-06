package org.nexus.bridge.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * 桥交易模型，记录一次跨链操作的完整生命周期。
 *
 * <p>每笔跨链操作（LOCK、MINT、BURN、UNLOCK）都会创建一条桥交易记录，
 * 记录源链与目标链信息、金额、状态、关联交易哈希等关键数据。</p>
 *
 * <h2>状态流转</h2>
 * <pre>
 *   正向跨链:  LOCK_PENDING ─► LOCKED ─► MINT_PENDING ─► MINTED
 *   反向跨链:  BURN_PENDING ─► BURNED ─► UNLOCK_PENDING ─► UNLOCKED
 *
 *   异常终态:  FAILED / CANCELLED / TIMEOUT
 * </pre>
 *
 * @since 1.0.0
 */
@Entity
@Table(name = "bridge_transactions")
public class BridgeTransaction {

    /** 桥交易唯一 ID。 */
    @Id
    @Column(name = "tx_id", length = 64)
    private String txId;

    /** 桥操作类型。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 32)
    private BridgeOperationType operationType;

    /** 交易状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private BridgeTxStatus status;

    /** 源链 ID。 */
    @Column(name = "source_chain_id", length = 64)
    private String sourceChainId;

    /** 目标链 ID。 */
    @Column(name = "target_chain_id", length = 64)
    private String targetChainId;

    /** 跨链金额（NEX 最小单位）。 */
    @Column(name = "amount", nullable = false)
    private long amount;

    /** 用户源链地址。 */
    @Column(name = "user_address", length = 128)
    private String userAddress;

    /** 用户目标链接收地址。 */
    @Column(name = "target_address", length = 128)
    private String targetAddress;

    /** 源链交易哈希。 */
    @Column(name = "source_tx_hash", length = 128)
    private String sourceTxHash;

    /** 目标链交易哈希。 */
    @Column(name = "target_tx_hash", length = 128)
    private String targetTxHash;

    /** 关联桥交易 ID（MINT 关联 LOCK，UNLOCK 关联 BURN）。 */
    @Column(name = "related_tx_id", length = 64)
    private String relatedTxId;

    /** 参与签名的验证者 ID 集合。 */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "bridge_tx_validators", joinColumns = @JoinColumn(name = "tx_id"))
    @Column(name = "validator_id")
    private Set<String> validatorIds = new HashSet<>();

    /** 创建时间。 */
    @Column(name = "created_at")
    private Instant createdAt;

    /** 最后更新时间。 */
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** 时间锁到期时间（大额跨链时设置）。 */
    @Column(name = "timelock_expires_at")
    private Instant timelockExpiresAt;

    /** 失败原因（状态为 FAILED 时记录，便于审计与恢复决策）。 */
    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    /** 备注信息。 */
    @Column(name = "memo", length = 512)
    private String memo;

    /**
     * 桥操作类型枚举。
     */
    public enum BridgeOperationType {
        /** 原链锁定。 */
        BRIDGE_LOCK,
        /** 目标链铸造。 */
        BRIDGE_MINT,
        /** 目标链销毁。 */
        BRIDGE_BURN,
        /** 原链解锁。 */
        BRIDGE_UNLOCK
    }

    /**
     * 桥交易状态枚举。
     */
    public enum BridgeTxStatus {
        /** 锁定待确认。 */
        LOCK_PENDING,
        /** 锁定已完成。 */
        LOCKED,
        /** 铸造待执行。 */
        MINT_PENDING,
        /** 铸造已完成。 */
        MINTED,
        /** 销毁待确认。 */
        BURN_PENDING,
        /** 销毁已完成。 */
        BURNED,
        /** 解锁待执行。 */
        UNLOCK_PENDING,
        /** 解锁已完成。 */
        UNLOCKED,
        /** 交易失败。 */
        FAILED,
        /** 交易已取消。 */
        CANCELLED,
        /** 交易超时。 */
        TIMEOUT
    }

    /**
     * 默认构造函数。
     */
    public BridgeTransaction() {
    }

    public String getTxId() {
        return txId;
    }

    public void setTxId(String txId) {
        this.txId = txId;
    }

    public BridgeOperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(BridgeOperationType operationType) {
        this.operationType = operationType;
    }

    public BridgeTxStatus getStatus() {
        return status;
    }

    public void setStatus(BridgeTxStatus status) {
        this.status = status;
    }

    public String getSourceChainId() {
        return sourceChainId;
    }

    public void setSourceChainId(String sourceChainId) {
        this.sourceChainId = sourceChainId;
    }

    public String getTargetChainId() {
        return targetChainId;
    }

    public void setTargetChainId(String targetChainId) {
        this.targetChainId = targetChainId;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getUserAddress() {
        return userAddress;
    }

    public void setUserAddress(String userAddress) {
        this.userAddress = userAddress;
    }

    public String getTargetAddress() {
        return targetAddress;
    }

    public void setTargetAddress(String targetAddress) {
        this.targetAddress = targetAddress;
    }

    public String getSourceTxHash() {
        return sourceTxHash;
    }

    public void setSourceTxHash(String sourceTxHash) {
        this.sourceTxHash = sourceTxHash;
    }

    public String getTargetTxHash() {
        return targetTxHash;
    }

    public void setTargetTxHash(String targetTxHash) {
        this.targetTxHash = targetTxHash;
    }

    public String getRelatedTxId() {
        return relatedTxId;
    }

    public void setRelatedTxId(String relatedTxId) {
        this.relatedTxId = relatedTxId;
    }

    public java.util.Set<String> getValidatorIds() {
        return validatorIds;
    }

    public void setValidatorIds(java.util.Set<String> validatorIds) {
        this.validatorIds = validatorIds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getTimelockExpiresAt() {
        return timelockExpiresAt;
    }

    public void setTimelockExpiresAt(Instant timelockExpiresAt) {
        this.timelockExpiresAt = timelockExpiresAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    /**
     * 判断该交易是否处于终态（不可再流转）。
     *
     * @return 终态返回 {@code true}，否则返回 {@code false}
     */
    public boolean isTerminal() {
        return status == BridgeTxStatus.MINTED
                || status == BridgeTxStatus.UNLOCKED
                || status == BridgeTxStatus.FAILED
                || status == BridgeTxStatus.CANCELLED
                || status == BridgeTxStatus.TIMEOUT;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BridgeTransaction that = (BridgeTransaction) o;
        return Objects.equals(txId, that.txId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(txId);
    }

    @Override
    public String toString() {
        return "BridgeTransaction{"
                + "txId='" + txId + '\''
                + ", operationType=" + operationType
                + ", status=" + status
                + ", sourceChainId='" + sourceChainId + '\''
                + ", targetChainId='" + targetChainId + '\''
                + ", amount=" + amount
                + ", createdAt=" + createdAt
                + '}';
    }
}
