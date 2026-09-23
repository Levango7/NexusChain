package org.nexus.gateway.risk;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 设备指纹实体，用于反欺诈检测。
 *
 * <p>通过收集浏览器/客户端特征（UA、IP、屏幕分辨率、时区等）生成 SHA-256 哈希，
 * 追踪设备与支付地址的关联关系，识别多账号、异常交易等风险行为。</p>
 */
@Entity
@Table(name = "device_fingerprints")
public class DeviceFingerprint {

    /** 风险等级枚举 */
    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH,
        BLACKLISTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 设备指纹哈希，唯一标识一个设备 */
    @Column(name = "fingerprint_hash", nullable = false, unique = true, length = 128)
    private String fingerprintHash;

    /** 关联商户 ID */
    @Column(name = "merchant_id")
    private Long merchantId;

    /** 关联支付方地址 */
    @Column(name = "payer_address", length = 66)
    private String payerAddress;

    /** 浏览器 User-Agent */
    @Column(name = "user_agent", length = 512)
    private String userAgent;

    /** IP 地址 */
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /** Accept-Language 头 */
    @Column(name = "accept_language", length = 64)
    private String acceptLanguage;

    /** 屏幕分辨率，如 "1920x1080" */
    @Column(name = "screen_resolution", length = 32)
    private String screenResolution;

    /** 时区，如 "Asia/Shanghai" */
    @Column(name = "timezone", length = 64)
    private String timezone;

    /** 平台，如 "Win32"/"MacIntel" */
    @Column(name = "platform", length = 32)
    private String platform;

    /** 首次出现时间 */
    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    /** 最后出现时间 */
    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;

    /** 风险等级，默认 LOW */
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 16)
    private RiskLevel riskLevel = RiskLevel.LOW;

    /** 是否在黑名单中 */
    @Column(name = "blacklisted", nullable = false)
    private boolean blacklisted = false;

    /** 关联的其他 payerAddress，逗号分隔 */
    @Column(name = "linked_addresses", length = 2048)
    private String linkedAddresses;

    /** 该指纹关联的交易总数 */
    @Column(name = "transaction_count", nullable = false)
    private int transactionCount = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 乐观锁版本号 */
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

    public String getFingerprintHash() { return fingerprintHash; }
    public void setFingerprintHash(String fingerprintHash) { this.fingerprintHash = fingerprintHash; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getAcceptLanguage() { return acceptLanguage; }
    public void setAcceptLanguage(String acceptLanguage) { this.acceptLanguage = acceptLanguage; }

    public String getScreenResolution() { return screenResolution; }
    public void setScreenResolution(String screenResolution) { this.screenResolution = screenResolution; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }

    public LocalDateTime getFirstSeenAt() { return firstSeenAt; }
    public void setFirstSeenAt(LocalDateTime firstSeenAt) { this.firstSeenAt = firstSeenAt; }

    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(RiskLevel riskLevel) { this.riskLevel = riskLevel; }

    public boolean isBlacklisted() { return blacklisted; }
    public void setBlacklisted(boolean blacklisted) { this.blacklisted = blacklisted; }

    public String getLinkedAddresses() { return linkedAddresses; }
    public void setLinkedAddresses(String linkedAddresses) { this.linkedAddresses = linkedAddresses; }

    public int getTransactionCount() { return transactionCount; }
    public void setTransactionCount(int transactionCount) { this.transactionCount = transactionCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}