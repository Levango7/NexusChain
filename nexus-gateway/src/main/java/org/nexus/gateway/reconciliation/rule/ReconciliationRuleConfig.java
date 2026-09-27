package org.nexus.gateway.reconciliation.rule;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对账规则配置 JPA 实体 — 支持多层级配置（全局/渠道/商户/商户+渠道）。
 *
 * <p>配置优先级：商户+渠道 > 商户+null > null+渠道 > null+null（全局默认）。</p>
 */
@Entity
@Table(name = "reconciliation_rule_configs")
public class ReconciliationRuleConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = true)
    private Long merchantId;

    @Column(name = "channel_type", length = 16, nullable = true)
    private String channelType;

    @Column(name = "amount_tolerance", precision = 36, scale = 2)
    private BigDecimal amountTolerance = new BigDecimal("0.01");

    @Column(name = "time_window_minutes")
    private Integer timeWindowMinutes = 5;

    @Column(name = "status_mapping_json", columnDefinition = "TEXT")
    private String statusMappingJson;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // === Getters & Setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    public BigDecimal getAmountTolerance() { return amountTolerance; }
    public void setAmountTolerance(BigDecimal amountTolerance) { this.amountTolerance = amountTolerance; }

    public Integer getTimeWindowMinutes() { return timeWindowMinutes; }
    public void setTimeWindowMinutes(Integer timeWindowMinutes) { this.timeWindowMinutes = timeWindowMinutes; }

    public String getStatusMappingJson() { return statusMappingJson; }
    public void setStatusMappingJson(String statusMappingJson) { this.statusMappingJson = statusMappingJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}