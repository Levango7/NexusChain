package org.nexus.gateway.apikey;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * API Key 实体 — JPA 映射 api_keys 表。
 *
 * <p>每个 API Key 属于一个商户（merchantId），拥有独立的权限范围（scopes）、
 * 状态（status）和过期时间（expireAt）。Key 的密钥（keySecret）以 SHA-256
 * 哈希存储，明文仅在创建时返回一次。</p>
 *
 * <p>生命周期：{@code ACTIVE}（正常使用）→ {@code ROTATED}（被轮换替代）
 * / {@code REVOKED}（被手动撤销）/ {@code EXPIRED}（超过过期时间）。</p>
 *
 * <p>轮换（rotate）时，旧 Key 标记为 {@code ROTATED} 并通过 {@code rotatedFromId}
 * 关联新 Key；新 Key 的 {@code rotatedFromId} 指向旧 Key 的 keyId。</p>
 */
@Entity
@Table(name = "api_keys",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_api_keys_key_id", columnNames = "key_id")
        },
        indexes = {
                @Index(name = "idx_api_keys_merchant_id", columnList = "merchant_id"),
                @Index(name = "idx_api_keys_status", columnList = "status"),
                @Index(name = "idx_api_keys_merchant_status", columnList = "merchant_id,status")
        })
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 公开标识（如 "ak_live_xxxx"），用于 API 请求头中携带。 */
    @Column(name = "key_id", nullable = false, length = 64)
    private String keyId;

    /** 加密存储的密钥哈希（SHA-256），不存明文。 */
    @JsonIgnore
    @Column(name = "key_secret", nullable = false, length = 256)
    private String keySecret;

    /** 所属商户 ID（与 tenants 表 tenant_id 关联）。 */
    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    /** 权限范围（逗号分隔的 ApiKeyScope 名称，如 "PAYMENTS,REFUNDS"）。 */
    @Column(name = "scopes", nullable = false, length = 256)
    private String scopes;

    /** Key 状态：ACTIVE / EXPIRED / REVOKED / ROTATED。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ApiKeyStatus status = ApiKeyStatus.ACTIVE;

    /** 过期时间（null 表示永不过期）。 */
    @Column(name = "expire_at")
    private LocalDateTime expireAt;

    /** 轮换时关联的旧 Key 的 keyId（新 Key 指向被轮换的旧 Key）。 */
    @Column(name = "rotated_from_id", length = 64)
    private String rotatedFromId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 最后使用时间（每次验证成功后更新）。 */
    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    /** 撤销时间。 */
    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    /** 撤销原因。 */
    @Column(name = "revoked_reason", length = 512)
    private String revokedReason;

    /** Key 用途描述（人类可读）。 */
    @Column(name = "description", length = 512)
    private String description;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) {
            this.status = ApiKeyStatus.ACTIVE;
        }
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getKeySecret() { return keySecret; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }

    public ApiKeyStatus getStatus() { return status; }
    public void setStatus(ApiKeyStatus status) { this.status = status; }

    public LocalDateTime getExpireAt() { return expireAt; }
    public void setExpireAt(LocalDateTime expireAt) { this.expireAt = expireAt; }

    public String getRotatedFromId() { return rotatedFromId; }
    public void setRotatedFromId(String rotatedFromId) { this.rotatedFromId = rotatedFromId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public LocalDateTime getRevokedAt() { return revokedAt; }
    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }

    public String getRevokedReason() { return revokedReason; }
    public void setRevokedReason(String revokedReason) { this.revokedReason = revokedReason; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}