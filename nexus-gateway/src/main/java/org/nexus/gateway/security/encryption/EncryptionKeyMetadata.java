package org.nexus.gateway.security.encryption;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 加密密钥元数据实体 — 映射 encryption_key_metadata 表。
 *
 * <p>每条记录对应一个字段的 DEK（Data Encryption Key），以密文形式存储。
 * DEK 由 KEK（Key Encryption Key）加密后存入 encrypted_dek 列，
 * 解密时需先获取对应版本的 KEK 解密 DEK，再用 DEK 解密业务数据。</p>
 *
 * <p>KEK 版本化支持密钥轮换：轮换后旧版本 DEK 渐进式迁移至新版本 KEK，
 * 迁移完成后旧版本记录标记为 ARCHIVED。</p>
 */
@Entity
@Table(name = "encryption_key_metadata")
public class EncryptionKeyMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "field_name", nullable = false, length = 64)
    private String fieldName;

    @Column(name = "encryption_algorithm", nullable = false, length = 32)
    private String encryptionAlgorithm = "AES-256-GCM";

    @Column(name = "kek_version", nullable = false)
    private Integer kekVersion;

    @Column(name = "encrypted_dek", nullable = false)
    private byte[] encryptedDek;

    @Column(name = "iv", nullable = false)
    private byte[] iv;

    @Column(name = "auth_tag", nullable = false)
    private byte[] authTag;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private KeyMetadataStatus status = KeyMetadataStatus.ACTIVE;

    @Version
    @Column(name = "version")
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }

    public String getEncryptionAlgorithm() { return encryptionAlgorithm; }
    public void setEncryptionAlgorithm(String encryptionAlgorithm) { this.encryptionAlgorithm = encryptionAlgorithm; }

    public Integer getKekVersion() { return kekVersion; }
    public void setKekVersion(Integer kekVersion) { this.kekVersion = kekVersion; }

    public byte[] getEncryptedDek() { return encryptedDek; }
    public void setEncryptedDek(byte[] encryptedDek) { this.encryptedDek = encryptedDek; }

    public byte[] getIv() { return iv; }
    public void setIv(byte[] iv) { this.iv = iv; }

    public byte[] getAuthTag() { return authTag; }
    public void setAuthTag(byte[] authTag) { this.authTag = authTag; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getRotatedAt() { return rotatedAt; }
    public void setRotatedAt(Instant rotatedAt) { this.rotatedAt = rotatedAt; }

    public KeyMetadataStatus getStatus() { return status; }
    public void setStatus(KeyMetadataStatus status) { this.status = status; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}