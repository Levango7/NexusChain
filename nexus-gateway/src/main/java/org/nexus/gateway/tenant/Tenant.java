package org.nexus.gateway.tenant;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 租户实体（P4-T6 多租户改造）。
 *
 * <p>每个租户代表一个独立的客户（商户/平台方），通过 {@code apiKey} + {@code apiSecret}
 * 鉴权，所有数据（订单、退款、订阅等）通过 {@code tenantId} 实现行级隔离。</p>
 *
 * <p>租户配置 {@link TenantConfig} 以 {@link Embedded} 形式存储在本表的 config_* 列中，
 * 避免额外查询。租户状态机：{@link TenantStatus#ACTIVE} → {@link TenantStatus#SUSPENDED}
 * → {@link TenantStatus#TERMINATED}。</p>
 */
@Entity
@Table(name = "tenants",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_tenants_tenant_id", columnNames = "tenant_id"),
                @UniqueConstraint(name = "uk_tenants_api_key", columnNames = "api_key")
        },
        indexes = {
                @Index(name = "idx_tenants_status", columnList = "status")
        })
public class Tenant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务租户 ID（UUID，对外暴露，用作数据隔离键）。 */
    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    /** 租户名称（人类可读）。 */
    @Column(name = "name", nullable = false, length = 128)
    private String name;

    /** 租户状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private TenantStatus status = TenantStatus.ACTIVE;

    /** API Key（请求头 X-Tenant-Api-Key 携带）。 */
    @Column(name = "api_key", nullable = false, length = 128)
    private String apiKey;

    /** API Secret（HMAC 签名验证用，存储 SHA-256 哈希）。 */
    @Column(name = "api_secret", nullable = false, length = 256)
    private String apiSecret;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 租户配置（限流/费率/币种白名单等）。 */
    @Embedded
    private TenantConfig config = new TenantConfig();

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.tenantId == null) {
            this.tenantId = UUID.randomUUID().toString().replace("-", "");
        }
        if (this.apiKey == null) {
            this.apiKey = UUID.randomUUID().toString().replace("-", "");
        }
        if (this.apiSecret == null) {
            this.apiSecret = UUID.randomUUID().toString().replace("-", "");
        }
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

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public TenantStatus getStatus() { return status; }
    public void setStatus(TenantStatus status) { this.status = status; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public TenantConfig getConfig() { return config; }
    public void setConfig(TenantConfig config) { this.config = config; }
}