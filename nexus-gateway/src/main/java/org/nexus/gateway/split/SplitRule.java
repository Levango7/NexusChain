package org.nexus.gateway.split;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 分账规则实体。
 *
 * <p>定义商户的分账/分润规则。支持两种分账类型：</p>
 * <ul>
 *   <li><b>RATIO</b>：按比例分账，{@link #splitValue} 为基点（10000 = 100%）</li>
 *   <li><b>FIXED</b>：固定金额分账，{@link #splitValue} 为绝对金额</li>
 * </ul>
 *
 * <p>FIXED 规则优先级高于 RATIO 规则（priority 数值小的先执行）。
 * 同一商户的 RATIO 规则总和不能超过 10000 基点（防止超额分账）。</p>
 */
@Entity
@Table(name = "split_rules")
public class SplitRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 商户 ID，关联 merchants 表 */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 接收分账金额的钱包地址 */
    @Column(name = "receiver_address", nullable = false, length = 66)
    private String receiverAddress;

    /** 分账类型：RATIO（按比例）或 FIXED（固定金额） */
    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false, length = 16)
    private SplitType splitType;

    /**
     * 分账值：
     * <ul>
     *   <li>RATIO 模式：1-10000 的整数（基点，10000 = 100%）</li>
     *   <li>FIXED 模式：正数金额</li>
     * </ul>
     */
    @Column(name = "split_value", nullable = false, precision = 36, scale = 8)
    private BigDecimal splitValue;

    /** 优先级：数字小的先执行。FIXED 规则默认 0，RATIO 规则默认 10 */
    @Column(name = "priority", nullable = false)
    private Integer priority = 0;

    /** 规则描述，如"分销商佣金"、"平台费" */
    @Column(name = "description", length = 256)
    private String description;

    /** 是否活跃（停用规则不参与分账计算） */
    @Column(name = "active", nullable = false)
    private boolean active = true;

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

    // --- Enumerations ---

    /**
     * 分账类型枚举。
     */
    public enum SplitType {
        /** 按比例分账，splitValue 为基点（10000 = 100%） */
        RATIO,
        /** 固定金额分账，splitValue 为绝对金额 */
        FIXED
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getReceiverAddress() { return receiverAddress; }
    public void setReceiverAddress(String receiverAddress) { this.receiverAddress = receiverAddress; }

    public SplitType getSplitType() { return splitType; }
    public void setSplitType(SplitType splitType) { this.splitType = splitType; }

    public BigDecimal getSplitValue() { return splitValue; }
    public void setSplitValue(BigDecimal splitValue) { this.splitValue = splitValue; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}