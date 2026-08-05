package org.nexus.settlement.clearing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 账本分录实体。
 * <p>
 * 描述一笔复式记账中的单条分录：账户、借贷方向、金额与关联业务凭证。
 * 余额约定：账户净额 = 贷方合计 - 借方合计。
 * 商户可用余额账户（MERCHANT:*）记贷方增加；
 * 待结算负债账户（SETTLEMENT_PAYABLE）记借方减少。
 * </p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LedgerEntry {

    /** 分录 ID */
    @JsonProperty("entryId")
    private String entryId;

    /** 账户名 */
    @JsonProperty("account")
    private String account;

    /** 借贷方向 */
    @JsonProperty("direction")
    private Direction direction;

    /** 金额（正数） */
    @JsonProperty("amount")
    private BigDecimal amount;

    /** 关联业务凭证（如清算订单 ID） */
    @JsonProperty("reference")
    private String reference;

    /** 入账时间 */
    @JsonProperty("bookedAt")
    private Instant bookedAt;

    /** 借贷方向枚举 */
    public enum Direction {
        DEBIT,
        CREDIT
    }

    public LedgerEntry() {}

    public LedgerEntry(String entryId, String account, Direction direction,
                       BigDecimal amount, String reference, Instant bookedAt) {
        this.entryId = entryId;
        this.account = account;
        this.direction = direction;
        this.amount = amount;
        this.reference = reference;
        this.bookedAt = bookedAt;
    }

    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }

    public String getAccount() { return account; }
    public void setAccount(String account) { this.account = account; }

    public Direction getDirection() { return direction; }
    public void setDirection(Direction direction) { this.direction = direction; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public Instant getBookedAt() { return bookedAt; }
    public void setBookedAt(Instant bookedAt) { this.bookedAt = bookedAt; }
}
