package org.nexus.settlement.reconciliation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 对账记录实体（JPA 持久化，账务核心）。
 * <p>
 * 对账的最小比对单元：一笔资金流水（本地清算记录、链上记录或银行记录
 * 统一映射为本结构），以 {@code reference} 为对账键、{@code amount} 为比对值。
 * </p>
 *
 * <p>持久化设计：{@code source}（CHAIN/BANK）区分链上/银行记录共用一表，
 * {@code UNIQUE(reference, source)} 复刻内存实现按 reference 幂等去重的语义。
 * 保留既有构造器（无参/四参），Jackson 序列化零破坏。</p>
 */
@Entity
@Table(name = "settlement_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_settlement_record_ref_src",
                columnNames = {"reference", "source"}))
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SettlementRecord {

    /** 数据源：链上记录 */
    public static final String SOURCE_CHAIN = "CHAIN";
    /** 数据源：银行渠道记录 */
    public static final String SOURCE_BANK = "BANK";

    /** 自增主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 对账键（如清算订单 ID / 链上交易哈希 / 银行流水号） */
    @Column(name = "reference", length = 128, nullable = false)
    @JsonProperty("reference")
    private String reference;

    /** 数据源（CHAIN / BANK） */
    @Column(name = "source", length = 16, nullable = false)
    @JsonProperty("source")
    private String source;

    /** 金额（正数） */
    @Column(name = "amount", precision = 36, scale = 8, nullable = false)
    @JsonProperty("amount")
    private BigDecimal amount;

    /** 币种 */
    @Column(name = "currency", length = 8)
    @JsonProperty("currency")
    private String currency;

    /** 记录时间 */
    @Column(name = "recorded_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    @JsonProperty("recordedAt")
    private Instant recordedAt;

    public SettlementRecord() {}

    public SettlementRecord(String reference, BigDecimal amount, String currency, Instant recordedAt) {
        this.reference = reference;
        this.amount = amount;
        this.currency = currency;
        this.recordedAt = recordedAt;
    }

    public SettlementRecord(String reference, String source, BigDecimal amount,
                            String currency, Instant recordedAt) {
        this.reference = reference;
        this.source = source;
        this.amount = amount;
        this.currency = currency;
        this.recordedAt = recordedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getReference() { return reference; }
    public void setReference(String reference) { this.reference = reference; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}