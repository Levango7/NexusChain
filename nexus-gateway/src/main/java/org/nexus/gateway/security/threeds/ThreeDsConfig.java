package org.nexus.gateway.security.threeds;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 3DS 参数配置实体，映射 {@code three_ds_configs} 表。
 *
 * <p>支持租户级和商户级两层配置：商户级配置优先，无商户级配置时回退租户级。
 * {@code merchantId} 为 {@code null} 表示租户级默认配置。</p>
 */
@Entity
@Table(name = "three_ds_configs")
public class ThreeDsConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 租户 ID（数据隔离键）。 */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 商户 ID（NULL 表示租户级配置）。 */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** 3DS 启用开关，默认关闭。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = false;

    /** Frictionless 阈值分数（0-100），低于此分数走 Frictionless 流程。 */
    @Column(name = "frictionless_threshold_score", nullable = false)
    private int frictionlessThresholdScore = 60;

    /** Challenge 超时时间（秒），默认 300 秒（5 分钟）。 */
    @Column(name = "challenge_timeout_seconds", nullable = false)
    private int challengeTimeoutSeconds = 300;

    /** ACS URL（模拟 ACS 的挑战地址）。 */
    @Column(name = "acs_url", length = 512)
    private String acsUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁版本。 */
    @Version
    @Column(name = "version")
    private Long version;

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

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getFrictionlessThresholdScore() { return frictionlessThresholdScore; }
    public void setFrictionlessThresholdScore(int frictionlessThresholdScore) {
        this.frictionlessThresholdScore = frictionlessThresholdScore;
    }

    public int getChallengeTimeoutSeconds() { return challengeTimeoutSeconds; }
    public void setChallengeTimeoutSeconds(int challengeTimeoutSeconds) {
        this.challengeTimeoutSeconds = challengeTimeoutSeconds;
    }

    public String getAcsUrl() { return acsUrl; }
    public void setAcsUrl(String acsUrl) { this.acsUrl = acsUrl; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}