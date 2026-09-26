package org.nexus.gateway.security.password;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 二次验证记录实体，映射 second_factor_records 表。
 *
 * <p>记录每次二次验证（OTP/TOTP/EMAIL）的验证码哈希、过期时间、消费状态。
 * 验证码以 bcrypt 哈希存储，5 分钟内有效，验证成功后标记 consumed=true。</p>
 */
@Entity
@Table(name = "second_factor_records")
public class SecondFactorRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "factor_type", nullable = false, length = 16)
    private FactorType factorType;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed", nullable = false)
    private boolean consumed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public FactorType getFactorType() { return factorType; }
    public void setFactorType(FactorType factorType) { this.factorType = factorType; }

    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public boolean isConsumed() { return consumed; }
    public void setConsumed(boolean consumed) { this.consumed = consumed; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}