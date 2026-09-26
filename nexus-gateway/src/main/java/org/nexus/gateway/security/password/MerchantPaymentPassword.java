package org.nexus.gateway.security.password;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * 商户支付密码实体，映射 merchant_payment_passwords 表。
 *
 * <p>每个商户最多一条支付密码记录。密码以 bcrypt 哈希存储，
 * 连续验证失败达到上限后自动锁定，锁定到期或管理员手动解锁后恢复。</p>
 *
 * <p>使用 {@code @Version} 实现乐观锁，防止并发更新导致失败计数错位。</p>
 */
@Entity
@Table(name = "merchant_payment_passwords")
public class MerchantPaymentPassword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "salt", length = 255)
    private String salt;

    @Column(name = "bcrypt_cost", nullable = false)
    private int bcryptCost = 10;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PasswordStatus status = PasswordStatus.ACTIVE;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts = 0;

    @Column(name = "last_changed_at", nullable = false)
    private Instant lastChangedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getSalt() { return salt; }
    public void setSalt(String salt) { this.salt = salt; }

    public int getBcryptCost() { return bcryptCost; }
    public void setBcryptCost(int bcryptCost) { this.bcryptCost = bcryptCost; }

    public PasswordStatus getStatus() { return status; }
    public void setStatus(PasswordStatus status) { this.status = status; }

    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }

    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int failedAttempts) { this.failedAttempts = failedAttempts; }

    public Instant getLastChangedAt() { return lastChangedAt; }
    public void setLastChangedAt(Instant lastChangedAt) { this.lastChangedAt = lastChangedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}