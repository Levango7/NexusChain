package org.nexus.gateway.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 持久化的商户密钥对条目（B-14 修复）。
 *
 * <p>{@link org.nexus.gateway.security.VaultKeyManager} 原先将加密后的密钥对仅保存在
 * 内存 {@code ConcurrentHashMap} 中，服务重启后所有商户密钥丢失导致认证失败。
 * 本实体将「已用 AES-256-GCM 加密的密钥对」落库，启动时由 VaultKeyManager 全量加载
 * 回内存，写入时同步 upsert 到此表，确保内存与数据库一致。</p>
 *
 * <p>表中只存储密文（Base64 编码的 {@code [IV || ciphertext || GCM-tag]}），
 * 主密钥由环境变量 {@code NEX_MASTER_KEY} 注入，不落库、不落配置。</p>
 */
@Entity
@Table(name = "merchant_keypairs",
        uniqueConstraints = @UniqueConstraint(name = "uk_merchant_keypairs_merchant_id", columnNames = "merchant_id"))
public class MerchantKeypairEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID，唯一约束保证一个商户只有一条密钥对记录。 */
    @Column(name = "merchant_id", nullable = false, unique = true)
    private Long merchantId;

    /** AES-256-GCM 加密后的密钥对密文（Base64 编码），格式与 VaultKeyManager 内部一致。 */
    @Column(name = "encrypted_keypair", nullable = false, length = 2048)
    private String encryptedKeypair;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getEncryptedKeypair() { return encryptedKeypair; }
    public void setEncryptedKeypair(String encryptedKeypair) { this.encryptedKeypair = encryptedKeypair; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}