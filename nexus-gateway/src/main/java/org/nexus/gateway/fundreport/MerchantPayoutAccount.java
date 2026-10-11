package org.nexus.gateway.fundreport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 商户出款去向（结算账户）绑定（P0 修复 2026-10-11）。
 *
 * <p><b>为什么需要它</b>：网关"自动提现"此前只扣商户账本余额、没有任何出款动作，
 * 且资金去向完全没有模型。资金该去哪、谁批的、是否有效，都没有落点。</p>
 *
 * <p><b>合规护栏（二清红线）</b>：出款去向必须是商户自己绑定的结算账户；
 * 平台侧的 {@code PLATFORM_SETTLEMENT} 账户要求 {@link #complianceEntity} =
 * true（持牌主体）才允许绑定——平台方用个人账户/非持牌主体收商户款再对外出款，
 * 即无牌资金归集与再清算，本类用类型约束把这条线画死。</p>
 *
 * <p><b>状态机</b>：{@code PENDING_VERIFY → ACTIVE / DISABLED}；
 * 账户变更按新记录走核验，不原地改写（留痕）。</p>
 */
@Entity
@Table(name = "merchant_payout_accounts")
public class MerchantPayoutAccount {

    /** 账户类型：银行卡 / 支付宝 / 平台结算账户（仅持牌主体）。 */
    public enum AccountType {
        BANK_CARD, ALIPAY, PLATFORM_SETTLEMENT
    }

    /** 状态：待核验 → 生效 / 停用。 */
    public enum AccountStatus {
        PENDING_VERIFY, ACTIVE, DISABLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 32)
    private AccountType accountType;

    /** 账户标识（卡号 / 支付宝账号）。 */
    @Column(name = "account_no", nullable = false, length = 128)
    private String accountNo;

    /** 户名（应与商户主体一致；不一致会在绑定时被拒）。 */
    @Column(name = "account_name", nullable = false, length = 128)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private AccountStatus status = AccountStatus.PENDING_VERIFY;

    /** 是否持牌主体（仅 PLATFORM_SETTLEMENT 有意义，且必须为 true 才允许绑定）。 */
    @Column(name = "compliance_entity", nullable = false)
    private boolean complianceEntity = false;

    @Column(name = "remark", length = 256)
    private String remark;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // === getters / setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public AccountType getAccountType() { return accountType; }
    public void setAccountType(AccountType accountType) { this.accountType = accountType; }
    public String getAccountNo() { return accountNo; }
    public void setAccountNo(String accountNo) { this.accountNo = accountNo; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }
    public boolean isComplianceEntity() { return complianceEntity; }
    public void setComplianceEntity(boolean complianceEntity) { this.complianceEntity = complianceEntity; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** 是否可作为出款去向（生效中）。 */
    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
