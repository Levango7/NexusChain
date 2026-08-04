package org.nexus.gateway.currency;

import java.math.BigDecimal;
import java.util.List;

/**
 * Currency service interface for multi-currency conversion and rate lookup.
 *
 * <p>Implementations typically source rates from an external price oracle
 * (e.g. CoinGecko, Chainlink) and cache them locally for the conversion
 * window.</p>
 */
public interface CurrencyService {

    /**
     * Convert an amount from one currency to another at the current rate.
     *
     * @param from   source currency
     * @param to     target currency
     * @param amount amount in the source currency
     * @return converted amount in the target currency
     */
    BigDecimal convert(Currency from, Currency to, BigDecimal amount);

    /**
     * Get the current exchange rate between two currencies.
     *
     * @param from source currency
     * @param to   target currency
     * @return the current exchange rate, or {@code null} if unavailable
     */
    ExchangeRate getExchangeRate(Currency from, Currency to);

    /**
     * List all currencies supported by this service.
     *
     * @return list of supported currencies
     */
    List<Currency> getSupportedCurrencies();
}