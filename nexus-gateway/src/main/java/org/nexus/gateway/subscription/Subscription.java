package org.nexus.gateway.subscription;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 订阅实体（P4-T8 订阅与循环计费引擎）。
 *
 * <p>表示商户与客户之间的循环扣款协议。引用 {@link SubscriptionPlan} 决定
 * 周期/金额，自身记录当前周期窗口、dunning 计数与生命周期时间戳。</p>
 *
 * <p>表名 {@code subscription_v2}，与 P1 简单订阅表 {@code subscriptions}
 * 分离，避免 JPA 验证冲突。包名 {@code org.nexus.gateway.subscription} 与
 * P1 的 {@code org.nexus.gateway.model.Subscription} 不同，类名相同不冲突，
 * 但 Hibernate entity name 必须唯一，因此显式指定为 {@code SubscriptionV2}。</p>
 */
@Entity(name = "SubscriptionV2")
@Table(name = "subscription_v2")
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订阅业务编号（全局唯一）。 */
    @Column(name = "subscription_id", unique = true, nullable = false, length = 64)
    private String subscriptionId;

    /** 商户 ID。 */
    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    /** 客户 ID（业务层标识，非链上地址）。 */
    @Column(name = "customer_id", nullable = false, length = 128)
    private String customerId;

    /** 付款人钱包地址。 */
    @Column(name = "payer_address", nullable = false, length = 66)
    private String payerAddress;

    /** 收款人钱包地址（商户结算钱包）。 */
    @Column(name = "payee_address", nullable = false, length = 66)
    private String payeeAddress;

    /** 关联的订阅计划 ID（业务编号）。 */
    @Column(name = "plan_id", nullable = false, length = 64)
    private String planId;

    /** 当前订阅状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private SubscriptionStatus status;

    /** 当前周期开始时间。 */
    @Column(name = "current_period_start", nullable = false)
    private LocalDateTime currentPeriodStart;

    /** 当前周期结束时间（下个周期开始时间）。 */
    @Column(name = "current_period_end", nullable = false)
    private LocalDateTime currentPeriodEnd;

    /** 试用期结束时间，null 表示无试用期或已转正。 */
    @Column(name = "trial_end")
    private LocalDateTime trialEnd;

    /** Dunning 重试计数（连续扣款失败次数，扣款成功后重置为 0）。 */
    @Column(name = "dunning_count", nullable = false)
    private int dunningCount = 0;

    /** 下次扣款时间（用于调度扫描）。 */
    @Column(name = "next_charge_at", nullable = false)
    private LocalDateTime nextChargeAt;

    /** 已成功扣款次数。 */
    @Column(name = "charged_count", nullable = false)
    private int chargedCount = 0;

    /** 最近一次扣款交易哈希。 */
    @Column(name = "last_tx_hash", length = 128)
    private String lastTxHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 取消时间，仅 status=CANCELLED 时非空。 */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /** 暂停时间，仅 status=PAUSED 时非空。 */
    @Column(name = "paused_at")
    private LocalDateTime pausedAt;

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

    public String getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public String getPayeeAddress() { return payeeAddress; }
    public void setPayeeAddress(String payeeAddress) { this.payeeAddress = payeeAddress; }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }

    public SubscriptionStatus getStatus() { return status; }
    public void setStatus(SubscriptionStatus status) { this.status = status; }

    public LocalDateTime getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(LocalDateTime currentPeriodStart) { this.currentPeriodStart = currentPeriodStart; }

    public LocalDateTime getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(LocalDateTime currentPeriodEnd) { this.currentPeriodEnd = currentPeriodEnd; }

    public LocalDateTime getTrialEnd() { return trialEnd; }
    public void setTrialEnd(LocalDateTime trialEnd) { this.trialEnd = trialEnd; }

    public int getDunningCount() { return dunningCount; }
    public void setDunningCount(int dunningCount) { this.dunningCount = dunningCount; }

    public LocalDateTime getNextChargeAt() { return nextChargeAt; }
    public void setNextChargeAt(LocalDateTime nextChargeAt) { this.nextChargeAt = nextChargeAt; }

    public int getChargedCount() { return chargedCount; }
    public void setChargedCount(int chargedCount) { this.chargedCount = chargedCount; }

    public String getLastTxHash() { return lastTxHash; }
    public void setLastTxHash(String lastTxHash) { this.lastTxHash = lastTxHash; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }

    public LocalDateTime getPausedAt() { return pausedAt; }
    public void setPausedAt(LocalDateTime pausedAt) { this.pausedAt = pausedAt; }
}