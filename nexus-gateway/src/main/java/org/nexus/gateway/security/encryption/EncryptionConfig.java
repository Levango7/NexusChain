package org.nexus.gateway.security.encryption;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 加密策略配置实体 — 映射 encryption_configs 表。
 *
 * <p>支持租户级和商户级配置：merchant_id 为 NULL 表示租户全局策略，
 * 非 NULL 表示商户专属策略。查询时商户级配置优先，无则回退全局配置。</p>
 *
 * <p>encrypted_fields 以 JSON 字符串存储需加密的字段名列表，
 * 如 {@code ["cardNumber", "cardHolderName", "cvv"]}。</p>
 */
@Entity
@Table(name = "encryption_configs")
public class EncryptionConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "encrypted_fields", nullable = false)
    private String encryptedFields;

    @Column(name = "encryption_algorithm", nullable = false, length = 32)
    private String encryptionAlgorithm = "AES-256-GCM";

    @Column(name = "kek_rotation_period_days", nullable = false)
    private Integer kekRotationPeriodDays = 90;

    @Column(name = "app_layer_encryption_enabled", nullable = false)
    private Boolean appLayerEncryptionEnabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getEncryptedFields() { return encryptedFields; }
    public void setEncryptedFields(String encryptedFields) { this.encryptedFields = encryptedFields; }

    public String getEncryptionAlgorithm() { return encryptionAlgorithm; }
    public void setEncryptionAlgorithm(String encryptionAlgorithm) { this.encryptionAlgorithm = encryptionAlgorithm; }

    public Integer getKekRotationPeriodDays() { return kekRotationPeriodDays; }
    public void setKekRotationPeriodDays(Integer kekRotationPeriodDays) { this.kekRotationPeriodDays = kekRotationPeriodDays; }

    public Boolean getAppLayerEncryptionEnabled() { return appLayerEncryptionEnabled; }
    public void setAppLayerEncryptionEnabled(Boolean appLayerEncryptionEnabled) { this.appLayerEncryptionEnabled = appLayerEncryptionEnabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}