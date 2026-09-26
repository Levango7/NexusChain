package org.nexus.gateway.security.replay;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 防重放保护配置实体。映射 {@code replay_protection_configs} 表。
 *
 * <p>每个租户一条配置记录，支持热更新。包含防重放窗口、nonce 最小长度、
 * 幂等性键 TTL 等可配置参数。使用 {@code @Version} 实现乐观锁，防止并发更新冲突。</p>
 *
 * <p>设计依据：Wave 12 设计文档 §3.2 V64、§5.2 决策5。</p>
 */
@Entity
@Table(name = "replay_protection_configs")
public class ReplayProtectionConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 防重放窗口（毫秒），默认 3 分钟。范围：60000~300000。 */
    @Column(name = "replay_window_ms", nullable = false)
    private Long replayWindowMs = 180000L;

    /** nonce 最小长度（字节），默认 16 字节（128 位）。 */
    @Column(name = "nonce_min_length_bytes", nullable = false)
    private Integer nonceMinLengthBytes = 16;

    /** 幂等性键 TTL（小时），默认 24 小时。范围：1~168。 */
    @Column(name = "idempotency_ttl_hours", nullable = false)
    private Integer idempotencyTtlHours = 24;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

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

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getReplayWindowMs() { return replayWindowMs; }
    public void setReplayWindowMs(Long replayWindowMs) { this.replayWindowMs = replayWindowMs; }

    public Integer getNonceMinLengthBytes() { return nonceMinLengthBytes; }
    public void setNonceMinLengthBytes(Integer nonceMinLengthBytes) { this.nonceMinLengthBytes = nonceMinLengthBytes; }

    public Integer getIdempotencyTtlHours() { return idempotencyTtlHours; }
    public void setIdempotencyTtlHours(Integer idempotencyTtlHours) { this.idempotencyTtlHours = idempotencyTtlHours; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}