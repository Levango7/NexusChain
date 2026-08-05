package org.nexus.gateway.currency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default currency service implementation.
 *
 * <p>Maintains an in-memory USD-based rate table: each supported currency has
 * a quoted USD value (1 unit of currency = X USD). Cross rates are derived as
 * {@code rate(from→to) = usdValue(from) / usdValue(to)}.</p>
 *
 * <p>Production wiring should refresh the table from an external price oracle
 * (CoinGecko / Chainlink / nexus-oracle) via {@link #setUsdValue}; the
 * conversion logic and spread handling remain unchanged.</p>
 *
 * <ul>
 *   <li>{@link #convert}：恒等短路 → 交叉汇率 → 应用点差（spread）→ amount × rate</li>
 *   <li>{@link #getExchangeRate}：从基准表推导交叉汇率，附带来源与报价时间</li>
 *   <li>{@link #getSupportedCurrencies}：返回基准表中已配置报价的币种子集</li>
 * </ul>
 */
@Service
public class DefaultCurrencyService implements CurrencyService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCurrencyService.class);

    /** Rate precision for derived cross rates. */
    private static final int RATE_SCALE = 18;

    /** Rate source label for table-derived rates. */
    private static final String SOURCE_BASE_TABLE = "BASE_TABLE";

    /** Conversion spread in basis points (e.g. 50 = 0.5%). Applied on top of the mid rate. */
    @Value("${nexus.gateway.currency.spread-bps:0}")
    private long spreadBps;

    /** USD-based rate table: currency → USD value of 1 unit. */
    private final Map<Currency, BigDecimal> usdValues = new ConcurrentHashMap<>();

    public DefaultCurrencyService() {
        // Default mid rates (overridable via setUsdValue / oracle sync)
        usdValues.put(Currency.USD, BigDecimal.ONE);
        usdValues.put(Currency.USDT, BigDecimal.ONE);
        usdValues.put(Currency.USDC, BigDecimal.ONE);
        usdValues.put(Currency.EUR, new BigDecimal("1.08"));
        usdValues.put(Currency.CNY, new BigDecimal("0.14"));
        usdValues.put(Currency.BTC, new BigDecimal("50000"));
        usdValues.put(Currency.ETH, new BigDecimal("3000"));
        usdValues.put(Currency.NEXUS, new BigDecimal("0.05"));
    }

    /**
     * Update the USD value for a currency (oracle sync / test hook).
     *
     * @param currency currency to update
     * @param usdValue USD value of 1 unit (must be positive)
     */
    public void setUsdValue(Currency currency, BigDecimal usdValue) {
        if (currency == null || usdValue == null || usdValue.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("currency and positive usdValue are required");
        }
        usdValues.put(currency, usdValue);
        log.info("USD value updated: {}={}", currency, usdValue);
    }

    /**
     * Remove a currency from the supported set (test hook).
     *
     * @param currency currency to remove
     */
    public void removeCurrency(Currency currency) {
        if (currency != null) {
            usdValues.remove(currency);
        }
    }

    @Override
    public BigDecimal convert(Currency from, Currency to, BigDecimal amount) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to currencies are required");
        }
        if (amount == null) {
            throw new IllegalArgumentException("amount is required");
        }
        // Identity conversion short-circuit
        if (from == to) {
            return amount;
        }
        ExchangeRate rate = getExchangeRate(from, to);
        if (rate == null || rate.getRate() == null) {
            throw new IllegalStateException("exchange rate unavailable: " + from + " -> " + to);
        }
        BigDecimal effectiveRate = applySpread(rate.getRate());
        BigDecimal converted = amount.multiply(effectiveRate);
        log.debug("convert: {} {} -> {} @ {} = {}", from, amount, to, effectiveRate, converted);
        return converted;
    }

    @Override
    public ExchangeRate getExchangeRate(Currency from, Currency to) {
        if (from == null || to == null) {
            return null;
        }
        BigDecimal fromUsd = usdValues.get(from);
        BigDecimal toUsd = usdValues.get(to);
        if (fromUsd == null || toUsd == null) {
            log.warn("getExchangeRate: missing USD value for {} or {}", from, to);
            return null;
        }
        // 1 from = fromUsd USD; 1 to = toUsd USD → rate = fromUsd / toUsd
        BigDecimal rate = fromUsd.divide(toUsd, RATE_SCALE, RoundingMode.HALF_UP);

        ExchangeRate er = new ExchangeRate();
        er.setFromCurrency(from);
        er.setToCurrency(to);
        er.setRate(rate);
        er.setQuotedAt(LocalDateTime.now());
        er.setSource(SOURCE_BASE_TABLE);
        return er;
    }

    @Override
    public List<Currency> getSupportedCurrencies() {
        return new ArrayList<>(usdValues.keySet());
    }

    /**
     * Apply the configured spread on top of the mid rate.
     * Spread is expressed in basis points; converted amount is reduced by the spread.
     */
    private BigDecimal applySpread(BigDecimal midRate) {
        if (spreadBps <= 0) {
            return midRate;
        }
        BigDecimal spreadFactor = BigDecimal.ONE.subtract(
                BigDecimal.valueOf(spreadBps).divide(BigDecimal.valueOf(10000), RATE_SCALE, RoundingMode.HALF_UP));
        return midRate.multiply(spreadFactor);
    }
}
