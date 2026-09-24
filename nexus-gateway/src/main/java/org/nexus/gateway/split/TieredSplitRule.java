package org.nexus.gateway.split;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 阶梯分账规则实体。
 *
 * <p>按金额区间配置不同分账比例。例如：</p>
 * <ul>
 *   <li>0-1000元：分账比例 10%（1000 基点）</li>
 *   <li>1000-10000元：分账比例 8%（800 基点）</li>
 *   <li>10000元以上：分账比例 5%（500 基点）</li>
 * </ul>
 *
 * <p>同一商户可配置多条阶梯规则，按 {@link #tierMinAmount} 升序排列。
 * 金额区间为左闭右开：[tierMinAmount, tierMaxAmount)。
 * 最高阶梯的 tierMaxAmount 为 null，表示无上限。</p>
 */
@Entity
@Table(name = "tiered_split_rules")
public class TieredSplitRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID，关联 merchants 表 */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 接收分账金额的钱包地址 */
    @Column(name = "receiver_address", nullable = false, length = 66)
    private String receiverAddress;

    /**
     * 阶梯最小金额（含），即该阶梯区间的下界。
     * 第一阶梯通常为 0。
     */
    @Column(name = "tier_min_amount", nullable = false, precision = 36, scale = 8)
    private BigDecimal tierMinAmount;

    /**
     * 阶梯最大金额（不含），即该阶梯区间的上界。
     * 最高阶梯为 null，表示无上限。
     */
    @Column(name = "tier_max_amount", precision = 36, scale = 8)
    private BigDecimal tierMaxAmount;

    /**
     * 分账比例（基点），1-10000 的整数（10000 = 100%）。
     * 如 1000 表示 10% 的分账比例。
     */
    @Column(name = "split_ratio", nullable = false, precision = 36, scale = 8)
    private BigDecimal splitRatio;

    /** 阶梯描述，如"0-1000元区间分账" */
    @Column(name = "description", length = 256)
    private String description;

    /** 是否活跃（停用规则不参与分账计算） */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    /** 阶梯序号，用于排序，数字小的先匹配 */
    @Column(name = "tier_order", nullable = false)
    private Integer tierOrder = 0;

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

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getReceiverAddress() { return receiverAddress; }
    public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }

    public BigDecimal getTierMinAmount() { return tierMinAmount; }
    public void setTierMinAmount(BigDecimal tierMinAmount) { this.tierMinAmount = tierMinAmount; }

    public BigDecimal getTierMaxAmount() { return tierMaxAmount; }
    public void setTierMaxAmount(BigDecimal tierMaxAmount) { this.tierMaxAmount = tierMaxAmount; }

    public BigDecimal getSplitRatio() { return splitRatio; }
    public void setSplitRatio(BigDecimal splitRatio) { this.splitRatio = splitRatio; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Integer getTierOrder() { return tierOrder; }
    public void setTierOrder(Integer tierOrder) { this.tierOrder = tierOrder; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    /**
     * 判断给定金额是否落在该阶梯区间内。
     *
     * @param amount 待判断的金额
     * @return 如果 amount >= tierMinAmount 且 (tierMaxAmount == null 或 amount < tierMaxAmount)，返回 true
     */
    public boolean matchesTier(BigDecimal amount) {
        if (amount.compareTo(tierMinAmount) < 0) {
            return false;
        }
        if (tierMaxAmount != null && amount.compareTo(tierMaxAmount) >= 0) {
            return false;
        }
        return true;
    }
}