package org.nexus.bridge.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 保险基金流水记录。
 *
 * <p>每笔存入 / 补偿操作记录一条流水，包含操作类型、金额、操作后余额、
 * 关联方（受害者 ID）、原因与时间戳。由 {@code DefaultInsuranceFund} 维护。</p>
 *
 * <h2>操作类型</h2>
 * <ul>
 *   <li>{@code DEPOSIT} — 存入保险基金</li>
 *   <li>{@code COMPENSATE} — 对受害者补偿</li>
 * </ul>
 *
 * @since 1.2
 */
@Entity
@Table(name = "insurance_fund_ledger")
public class InsuranceFundLedgerEntry {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_id")
    private Long entryId;

    /** 操作类型（DEPOSIT / COMPENSATE）。 */
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    /** 操作金额（正数）。 */
    @Column(name = "amount", nullable = false, precision = 38, scale = 18)
    private BigDecimal amount;

    /** 操作后余额。 */
    @Column(name = "balance_after", nullable = false, precision = 38, scale = 18)
    private BigDecimal balanceAfter;

    /** 关联方 ID（COMPENSATE 时为受害者 ID，DEPOSIT 时为存入者）。 */
    @Column(name = "party_id", length = 128)
    private String partyId;

    /** 操作原因 / 备注。 */
    @Column(name = "reason", length = 512)
    private String reason;

    /** 操作时间。 */
    @Column(name = "created_at")
    private Instant createdAt;

    /** 默认构造函数。 */
    public InsuranceFundLedgerEntry() {
    }

    /**
     * 全参数构造函数。
     *
     * @param type         操作类型
     * @param amount       金额
     * @param balanceAfter 操作后余额
     * @param partyId      关联方 ID
     * @param reason       原因
     */
    public InsuranceFundLedgerEntry(String type, BigDecimal amount,
                                     BigDecimal balanceAfter, String partyId, String reason) {
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.partyId = partyId;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public Long getEntryId() {
        return entryId;
    }

    public void setEntryId(Long entryId) {
        this.entryId = entryId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public String getPartyId() {
        return partyId;
    }

    public void setPartyId(String partyId) {
        this.partyId = partyId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        InsuranceFundLedgerEntry that = (InsuranceFundLedgerEntry) o;
        return Objects.equals(entryId, that.entryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entryId);
    }

    @Override
    public String toString() {
        return "InsuranceFundLedgerEntry{"
                + "entryId=" + entryId
                + ", type='" + type + '\''
                + ", amount=" + amount
                + ", balanceAfter=" + balanceAfter
                + ", partyId='" + partyId + '\''
                + ", createdAt=" + createdAt
                + '}';
    }
}