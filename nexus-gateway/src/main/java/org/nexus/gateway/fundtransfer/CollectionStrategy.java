package org.nexus.gateway.fundtransfer;

import jakarta.persistence.*;
import org.nexus.gateway.account.AccountType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 归集策略实体 — JPA 映射 collection_strategies 表。
 *
 * <p>定义将多个商户账户资金归集到指定目标账户的策略。归集类型支持：</p>
 * <ul>
 *   <li>FULL — 全额归集（保留最低保留金额后全部归集）</li>
 *   <li>PERCENTAGE — 按百分比归集</li>
 *   <li>FIXED — 固定金额归集</li>
 * </ul>
 *
 * <p>并发安全：使用 {@code @Version} 乐观锁。</p>
 */
@Entity
@Table(name = "collection_strategies")
public class CollectionStrategy {

    /** 归集类型：全额归集 */
    public static final String COLLECTION_TYPE_FULL = "FULL";

    /** 归集类型：按百分比归集 */
    public static final String COLLECTION_TYPE_PERCENTAGE = "PERCENTAGE";

    /** 归集类型：固定金额归集 */
    public static final String COLLECTION_TYPE_FIXED = "FIXED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 策略名称 */
    @Column(name = "strategy_name", nullable = false, length = 128)
    private String strategyName;

    /** 策略编号，唯一标识 */
    @Column(name = "strategy_code", unique = true, nullable = false, length = 64)
    private String strategyCode;

    /** 源商户ID列表，逗号分隔 */
    @Column(name = "source_merchant_ids", nullable = false, columnDefinition = "TEXT")
    private String sourceMerchantIds;

    /** 目标商户ID */
    @Column(name = "target_merchant_id", nullable = false)
    private Long targetMerchantId;

    /** 源账户类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_account_type", nullable = false, length = 32)
    private AccountType sourceAccountType;

    /** 目标账户类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_account_type", nullable = false, length = 32)
    private AccountType targetAccountType;

    /** 归集类型: FULL/PERCENTAGE/FIXED */
    @Column(name = "collection_type", nullable = false, length = 32)
    private String collectionType;

    /** 固定归集金额（FIXED 类型使用） */
    @Column(name = "collection_amount", precision = 36, scale = 0)
    private BigDecimal collectionAmount;

    /** 归集百分比（PERCENTAGE 类型使用，0-100） */
    @Column(name = "collection_percentage", precision = 8, scale = 4)
    private BigDecimal collectionPercentage;

    /** 源账户最低保留金额 */
    @Column(name = "min_retain_amount", nullable = false, precision = 36, scale = 0)
    private BigDecimal minRetainAmount = BigDecimal.ZERO;

    /** 定时表达式（定时归集使用） */
    @Column(name = "cron_expression", length = 64)
    private String cronExpression;

    /** 是否启用 */
    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    /** 策略描述 */
    @Column(name = "description", length = 512)
    private String description;

    /** 多租户隔离键 */
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    /** 乐观锁版本号 */
    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.enabled == null) {
            this.enabled = true;
        }
        if (this.minRetainAmount == null) {
            this.minRetainAmount = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Helpers ---

    /**
     * 解析源商户ID列表字符串为 Long 列表。
     *
     * @return 源商户ID列表
     */
    public List<Long> getSourceMerchantIdList() {
        List<Long> result = new ArrayList<>();
        if (sourceMerchantIds == null || sourceMerchantIds.isBlank()) {
            return result;
        }
        for (String token : sourceMerchantIds.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(Long.parseLong(trimmed));
            }
        }
        return result;
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStrategyName() { return strategyName; }
    public void setStrategyName(String strategyName) { this.strategyName = strategyName; }

    public String getStrategyCode() { return strategyCode; }
    public void setStrategyCode(String strategyCode) { this.strategyCode = strategyCode; }

    public String getSourceMerchantIds() { return sourceMerchantIds; }
    public void setSourceMerchantIds(String sourceMerchantIds) { this.sourceMerchantIds = sourceMerchantIds; }

    public Long getTargetMerchantId() { return targetMerchantId; }
    public void setTargetMerchantId(Long targetMerchantId) { this.targetMerchantId = targetMerchantId; }

    public AccountType getSourceAccountType() { return sourceAccountType; }
    public void setSourceAccountType(AccountType sourceAccountType) { this.sourceAccountType = sourceAccountType; }

    public AccountType getTargetAccountType() { return targetAccountType; }
    public void setTargetAccountType(AccountType targetAccountType) { this.targetAccountType = targetAccountType; }

    public String getCollectionType() { return collectionType; }
    public void setCollectionType(String collectionType) { this.collectionType = collectionType; }

    public BigDecimal getCollectionAmount() { return collectionAmount; }
    public void setCollectionAmount(BigDecimal collectionAmount) { this.collectionAmount = collectionAmount; }

    public BigDecimal getCollectionPercentage() { return collectionPercentage; }
    public void setCollectionPercentage(BigDecimal collectionPercentage) { this.collectionPercentage = collectionPercentage; }

    public BigDecimal getMinRetainAmount() { return minRetainAmount; }
    public void setMinRetainAmount(BigDecimal minRetainAmount) { this.minRetainAmount = minRetainAmount; }

    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}