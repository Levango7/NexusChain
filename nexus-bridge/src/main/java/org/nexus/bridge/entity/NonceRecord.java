package org.nexus.bridge.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * 重放保护 nonce 持久化记录（B-22 修复）。
 *
 * <p>用于跨链桥重放保护：将已使用的 nonce 持久化到 DB，
 * 节点重启后仍能防止重放旧交易。替代原内存存储方案，
 * 避免重启后所有 nonce 丢失导致攻击者可重放历史交易。</p>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code nonce} — nonce 值（主键，唯一标识一次桥操作）</li>
 *   <li>{@code createdAt} — 记录创建时间（用于过期清理）</li>
 * </ul>
 *
 * @since 2.28.0
 */
@Entity
@Table(name = "bridge_nonce_records")
public class NonceRecord {

    /** nonce 主键（桥操作的幂等键）。 */
    @Id
    @Column(name = "nonce", length = 128)
    private String nonce;

    /** 创建时间（用于过期清理）。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 默认构造函数（JPA 需要）。 */
    public NonceRecord() {
    }

    /**
     * 全参数构造函数。
     *
     * @param nonce     nonce 值
     * @param createdAt 创建时间
     */
    public NonceRecord(String nonce, Instant createdAt) {
        this.nonce = nonce;
        this.createdAt = createdAt;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NonceRecord that = (NonceRecord) o;
        return Objects.equals(nonce, that.nonce);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nonce);
    }

    @Override
    public String toString() {
        return "NonceRecord{nonce='" + nonce + "', createdAt=" + createdAt + '}';
    }
}