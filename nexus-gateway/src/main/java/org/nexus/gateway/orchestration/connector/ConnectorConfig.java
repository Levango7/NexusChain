package org.nexus.gateway.orchestration.connector;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Instant;

/**
 * 动态注册 Connector 的持久化配置实体。
 *
 * <p>存储通过 {@code POST /api/v1/payments/connectors} 动态注册的连接器配置，
 * 支持启动时自动恢复到 {@link ConnectorRegistry}。</p>
 *
 * <p>支持的类型：</p>
 * <ul>
 *   <li>{@code http_psp} — 通用 HTTP PSP，使用 {@code baseUrl} + {@code apiKeyEnv}</li>
 *   <li>{@code wechat} — 微信支付，使用 {@code appId} + {@code mchId} + {@code apiKeyEnv}</li>
 *   <li>{@code alipay} — 支付宝，使用 {@code appId} + {@code merchantPrivateKey} + {@code alipayPublicKey}</li>
 * </ul>
 *
 * <p>敏感信息策略：{@code merchantPrivateKey} 和 {@code alipayPublicKey} 字段
 * 存储的是环境变量名（而非实际密钥值），运行时通过 {@code System.getenv()} 解析。
 * 这与 {@code http_psp} 的 {@code apiKeyEnv} 模式一致。</p>
 */
@Entity
@Table(name = "connector_configs")
public class ConnectorConfig {

    @Id
    @Column(name = "id", length = 100, nullable = false)
    private String id;

    @Column(name = "type", length = 50, nullable = false)
    private String type;

    @Column(name = "display_name", length = 200)
    private String displayName;

    @Column(name = "base_url", length = 500)
    private String baseUrl;

    @Column(name = "api_key_env", length = 200)
    private String apiKeyEnv;

    @Column(name = "app_id", length = 200)
    private String appId;

    @Column(name = "mch_id", length = 200)
    private String mchId;

    @Column(name = "merchant_private_key", columnDefinition = "TEXT")
    private String merchantPrivateKey;

    @Column(name = "alipay_public_key", columnDefinition = "TEXT")
    private String alipayPublicKey;

    @Column(name = "currencies", length = 200)
    private String currencies;

    @Column(name = "fee_bps")
    private int feeBps;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    // === Getters & Setters ===

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKeyEnv() { return apiKeyEnv; }
    public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }

    public String getMchId() { return mchId; }
    public void setMchId(String mchId) { this.mchId = mchId; }

    public String getMerchantPrivateKey() { return merchantPrivateKey; }
    public void setMerchantPrivateKey(String merchantPrivateKey) { this.merchantPrivateKey = merchantPrivateKey; }

    public String getAlipayPublicKey() { return alipayPublicKey; }
    public void setAlipayPublicKey(String alipayPublicKey) { this.alipayPublicKey = alipayPublicKey; }

    public String getCurrencies() { return currencies; }
    public void setCurrencies(String currencies) { this.currencies = currencies; }

    public int getFeeBps() { return feeBps; }
    public void setFeeBps(int feeBps) { this.feeBps = feeBps; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}