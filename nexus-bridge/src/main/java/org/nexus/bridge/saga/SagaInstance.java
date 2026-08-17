package org.nexus.bridge.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Saga 实例持久化记录（P2-F2）。
 *
 * <p>每个跨链 Saga（lock→mint / burn→unlock）创建一条记录，
 * 用于崩溃恢复、重试与人工审计。状态机见 {@link SagaState}。</p>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code sagaType} — Saga 类型（LOCK_MINT / BURN_UNLOCK）</li>
 *   <li>{@code state} — 当前状态</li>
 *   <li>{@code currentStepIndex} — 当前执行到的步骤下标</li>
 *   <li>{@code payload} — Saga 上下文 JSON（请求快照、中间结果）</li>
 *   <li>{@code retryCount} — 已重试次数</li>
 *   <li>{@code lastError} — 最近一次错误信息</li>
 * </ul>
 *
 * @since 2.2.0
 */
@Entity
@Table(name = "saga_instances")
public class SagaInstance {

    /** Saga 主键 ID（UUID）。 */
    @Id
    @Column(name = "id", length = 64)
    private String id;

    /**
     * 乐观锁版本号（中5 改进）。
     *
     * <p>并发更新同一 Saga 实例时，JPA 通过 {@code @Version} 字段检测冲突：
     * 读取时记录 version，更新时 {@code UPDATE ... WHERE version = ?}，
     * 若行已被其他事务修改则影响行数为 0，抛出 {@code OptimisticLockException}，
     * 调用方捕获后重试或放弃，避免并发更新覆盖彼此的修改。</p>
     */
    @Version
    @Column(name = "version")
    private Long version;

    /** Saga 类型（LOCK_MINT / BURN_UNLOCK）。 */
    @Column(name = "saga_type", nullable = false, length = 32)
    private String sagaType;

    /** Saga 当前状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private SagaState state;

    /** 当前步骤下标（从 0 开始）。 */
    @Column(name = "current_step_index", nullable = false)
    private int currentStepIndex;

    /** Saga 上下文 JSON。 */
    @Lob
    @Column(name = "payload")
    private String payload;

    /** 关联的桥交易 ID（如 lockTxId / burnTxId）。 */
    @Column(name = "related_tx_id", length = 64)
    private String relatedTxId;

    /** 已重试次数。 */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    /** 最大重试次数。 */
    @Column(name = "max_retries", nullable = false)
    private int maxRetries;

    /** 最近一次错误信息。 */
    @Column(name = "last_error", length = 1024)
    private String lastError;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 最后更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 默认构造函数。 */
    public SagaInstance() {
    }

    /**
     * 创建新 Saga 实例（PENDING 状态）。
     *
     * @param sagaType    Saga 类型
     * @param payload     上下文 JSON
     * @param relatedTxId 关联桥交易 ID
     * @param maxRetries  最大重试次数
     */
    public SagaInstance(String sagaType, String payload, String relatedTxId, int maxRetries) {
        this.id = UUID.randomUUID().toString();
        this.sagaType = sagaType;
        this.state = SagaState.PENDING;
        this.currentStepIndex = 0;
        this.payload = payload;
        this.relatedTxId = relatedTxId;
        this.retryCount = 0;
        this.maxRetries = maxRetries;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getSagaType() {
        return sagaType;
    }

    public void setSagaType(String sagaType) {
        this.sagaType = sagaType;
    }

    public SagaState getState() {
        return state;
    }

    public void setState(SagaState state) {
        this.state = state;
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public void setCurrentStepIndex(int currentStepIndex) {
        this.currentStepIndex = currentStepIndex;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getRelatedTxId() {
        return relatedTxId;
    }

    public void setRelatedTxId(String relatedTxId) {
        this.relatedTxId = relatedTxId;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
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

    /**
     * 是否处于终态。
     *
     * @return COMPLETED、FAILED 或 CANCELLED 返回 {@code true}
     */
    public boolean isTerminal() {
        return state == SagaState.COMPLETED
                || state == SagaState.FAILED
                || state == SagaState.CANCELLED;
    }

    /**
     * 是否还可重试。
     *
     * @return retryCount < maxRetries 返回 {@code true}
     */
    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SagaInstance that = (SagaInstance) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "SagaInstance{"
                + "id='" + id + '\''
                + ", sagaType='" + sagaType + '\''
                + ", state=" + state
                + ", currentStepIndex=" + currentStepIndex
                + ", retryCount=" + retryCount
                + ", maxRetries=" + maxRetries
                + '}';
    }
}