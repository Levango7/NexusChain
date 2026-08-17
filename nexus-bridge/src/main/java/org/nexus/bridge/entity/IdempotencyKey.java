package org.nexus.bridge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 幂等键记录（P2-F2）。
 *
 * <p>用于跨链桥全链路幂等性保障：lock / mint / burn / unlock 操作执行前
 * 先按 {@code (key, operation)} 查询是否已存在有效记录，若存在且未过期
 * 则直接反序列化返回之前的结果，避免重复执行副作用操作。</p>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code key} — 幂等键，通常为 sourceTxHash 或客户端提供的 requestId</li>
 *   <li>{@code operation} — 操作类型（LOCK / MINT / BURN / UNLOCK）</li>
 *   <li>{@code result} — 操作结果（JSON 序列化的 BridgeTransaction）</li>
 *   <li>{@code createdAt} — 创建时间</li>
 *   <li>{@code expiresAt} — 过期时间（默认 24h），过期后允许同 key 重新执行</li>
 * </ul>
 *
 * <h2>唯一约束</h2>
 * <p>{@code (key, operation)} 联合唯一，DB 层硬性防止同一幂等键 + 同一操作
 * 并发写入两条记录。应用层先查后写，DB 层兜底。</p>
 *
 * @since 2.2.0
 */
@Entity
@Table(name = "idempotency_keys", uniqueConstraints = {
        @UniqueConstraint(name = "uk_idempotency_key_op",
                columnNames = {"key_value", "operation"})
})
public class IdempotencyKey {

    /** 主键 ID（UUID）。 */
    @Id
    @Column(name = "id", length = 64)
    private String id;

    /** 幂等键（sourceTxHash 或 requestId）。 */
    @Column(name = "key_value", nullable = false, length = 128)
    private String key;

    /** 操作类型（LOCK / MINT / BURN / UNLOCK）。 */
    @Column(name = "operation", nullable = false, length = 32)
    private String operation;

    /** 操作结果（JSON 序列化）。 */
    @Column(name = "result", nullable = false, length = 4096)
    private String result;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 过期时间（默认 createdAt + 24h）。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 默认构造函数（JPA 需要）。 */
    public IdempotencyKey() {
    }

    /**
     * 全参数构造函数。
     *
     * @param key        幂等键
     * @param operation  操作类型
     * @param result     操作结果 JSON
     * @param expiresAt  过期时间
     */
    public IdempotencyKey(String key, String operation, String result, Instant expiresAt) {
        this.id = UUID.randomUUID().toString();
        this.key = key;
        this.operation = operation;
        this.result = result;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    /**
     * 判断本幂等键是否已过期。
     *
     * @return 已过期返回 {@code true}，否则返回 {@code false}
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IdempotencyKey that = (IdempotencyKey) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "IdempotencyKey{"
                + "id='" + id + '\''
                + ", key='" + key + '\''
                + ", operation='" + operation + '\''
                + ", createdAt=" + createdAt
                + ", expiresAt=" + expiresAt
                + '}';
    }
}