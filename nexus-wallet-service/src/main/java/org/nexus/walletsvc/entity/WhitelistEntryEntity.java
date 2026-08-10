package org.nexus.walletsvc.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 地址白名单 Entity，映射 {@code address_whitelist} 表。
 *
 * <p>统一替代 {@code DefaultAddressWhitelistService.entries}（{@code ConcurrentHashMap}）
 * 与 {@code DefaultApprovalPolicy.whitelist}（{@code CopyOnWriteArraySet}）两套内存存储，
 * 消除 Phase 3 遗留的双重白名单问题（设计文档 §2.2 / §4.1.2 / §4.2.1）。</p>
 *
 * <p>软删除通过 {@code active=false} 实现，地址本身保持唯一约束（{@code uk_address}），
 * 重新激活时无需重新插入。</p>
 */
@Entity
@Table(name = "address_whitelist")
public class WhitelistEntryEntity {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 钱包地址（业务唯一键，{@code uk_address}）。 */
    @Column(name = "address", unique = true, nullable = false, length = 128)
    private String address;

    /** 地址标签（可空，人类可读说明，如 "Exchange hot wallet"）。 */
    @Column(name = "label", length = 256)
    private String label;

    /** 商户 ID。 */
    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    /** 加入白名单时间。 */
    @Column(name = "added_at", nullable = false)
    private LocalDateTime addedAt;

    /** 首次提币放行时间（{@code addedAt + delay}），可空。 */
    @Column(name = "first_withdrawal_available_at")
    private LocalDateTime firstWithdrawalAvailableAt;

    /** 是否活跃（软删除标记），默认 true。 */
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    /** 记录创建时间，由 {@link PrePersist} 自动维护。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 记录更新时间，由 {@link PreUpdate} 自动维护。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.addedAt == null) {
            this.addedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public LocalDateTime getAddedAt() { return addedAt; }
    public void setAddedAt(LocalDateTime addedAt) { this.addedAt = addedAt; }

    public LocalDateTime getFirstWithdrawalAvailableAt() { return firstWithdrawalAvailableAt; }
    public void setFirstWithdrawalAvailableAt(LocalDateTime firstWithdrawalAvailableAt) {
        this.firstWithdrawalAvailableAt = firstWithdrawalAvailableAt;
    }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}