package org.nexus.gateway.currency;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Exchange rate entity capturing a quoted rate between two currencies.
 *
 * <p>Rates are typically sourced from an external price oracle and refreshed
 * on a schedule. The {@link #source} field records the data provider for
 * auditability.</p>
 */
@Entity
@Table(name = "exchange_rates")
public class ExchangeRate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Source currency. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_currency", nullable = false, length = 16)
    private Currency fromCurrency;

    /** Target currency. */
    @Enumerated(EnumType.STRING)
    @Column(name = "to_currency", nullable = false, length = 16)
    private Currency toCurrency;

    /** Quoted rate: 1 unit of fromCurrency = rate units of toCurrency. */
    @Column(name = "rate", nullable = false, precision = 36, scale = 18)
    private BigDecimal rate;

    /** Timestamp when the rate was quoted by the source. */
    @Column(name = "quoted_at", nullable = false)
    private LocalDateTime quotedAt;

    /** Data source / oracle name (e.g. "CoinGecko", "Chainlink"). */
    @Column(name = "source", nullable = false, length = 64)
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Currency getFromCurrency() { return fromCurrency; }
    public void setFromCurrency(Currency fromCurrency) { this.fromCurrency = fromCurrency; }

    public Currency getToCurrency() { return toCurrency; }
    public void setToCurrency(Currency toCurrency) { this.toCurrency = toCurrency; }

    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal rate) { this.rate = rate; }

    public LocalDateTime getQuotedAt() { return quotedAt; }
    public void setQuotedAt(LocalDateTime quotedAt) { this.quotedAt = quotedAt; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}