package org.nexus.gateway.currency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Default skeleton implementation of {@link CurrencyService}.
 *
 * <p>All methods are stubbed and log a TODO marker. Production wiring should
 * fetch rates from an external price oracle (e.g. CoinGecko, Chainlink),
 * cache them, and apply a spread or fee on conversion.</p>
 */
@Service
public class DefaultCurrencyService implements CurrencyService {

    private static final Logger log = LoggerFactory.getLogger(DefaultCurrencyService.class);

    @Override
    public BigDecimal convert(Currency from, Currency to, BigDecimal amount) {
        // TODO: short-circuit identity conversion (from == to) returning amount
        // TODO: fetch cached rate for (from, to); if missing, refresh from oracle
        // TODO: apply spread/fee and return amount * rate
        log.warn("convert not implemented: from={}, to={}, amount={}", from, to, amount);
        return amount;
    }

    @Override
    public ExchangeRate getExchangeRate(Currency from, Currency to) {
        // TODO: look up the most recent cached ExchangeRate for (from, to)
        // TODO: if stale or missing, refresh from oracle and persist
        log.warn("getExchangeRate not implemented: from={}, to={}", from, to);
        ExchangeRate stub = new ExchangeRate();
        stub.setFromCurrency(from);
        stub.setToCurrency(to);
        stub.setRate(BigDecimal.ONE);
        stub.setQuotedAt(LocalDateTime.now());
        stub.setSource("stub");
        return stub;
    }

    @Override
    public List<Currency> getSupportedCurrencies() {
        // TODO: return the configured subset of supported currencies (may exclude some stablecoins)
        return Arrays.asList(Currency.values());
    }
}