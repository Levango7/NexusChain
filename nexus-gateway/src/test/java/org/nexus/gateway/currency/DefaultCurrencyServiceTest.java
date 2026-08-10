package org.nexus.gateway.currency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DefaultCurrencyService} 单元测试：验证恒等转换、交叉汇率、
 * 点差应用与币种子集管理。
 */
class DefaultCurrencyServiceTest {

    private DefaultCurrencyService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DefaultCurrencyService();
        setField(service, "spreadBps", 0L);
    }

    @Test
    void convert_identityReturnsAmount() {
        BigDecimal amount = new BigDecimal("123.45");
        assertEquals(0, amount.compareTo(service.convert(Currency.USD, Currency.USD, amount)));
        assertEquals(0, amount.compareTo(service.convert(Currency.BTC, Currency.BTC, amount)));
    }

    @Test
    void convert_stablecoinToStablecoin() {
        // USDT→USDC 都锚定 1 USD，1:1
        BigDecimal result = service.convert(Currency.USDT, Currency.USDC, new BigDecimal("100"));
        assertEquals(0, new BigDecimal("100").compareTo(result));
    }

    @Test
    void convert_crossRateBtcToEth() {
        // BTC=50000 USD, ETH=3000 USD → 1 BTC = 50000/3000 ETH
        BigDecimal result = service.convert(Currency.BTC, Currency.ETH, BigDecimal.ONE);
        BigDecimal expected = new BigDecimal("50000").divide(new BigDecimal("3000"), 18, RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(result));
    }

    @Test
    void convert_fiatCross() {
        // EUR=1.08 USD, CNY=0.14 USD → 1 EUR = 1.08/0.14 CNY
        BigDecimal result = service.convert(Currency.EUR, Currency.CNY, BigDecimal.ONE);
        BigDecimal expected = new BigDecimal("1.08").divide(new BigDecimal("0.14"), 18, RoundingMode.HALF_UP);
        assertEquals(0, expected.compareTo(result));
    }

    @Test
    void getExchangeRate_usdToUsdt() {
        ExchangeRate rate = service.getExchangeRate(Currency.USD, Currency.USDT);
        assertNotNull(rate);
        assertEquals(Currency.USD, rate.getFromCurrency());
        assertEquals(Currency.USDT, rate.getToCurrency());
        assertEquals(0, BigDecimal.ONE.compareTo(rate.getRate()));
        assertNotNull(rate.getQuotedAt());
        assertNotNull(rate.getSource());
    }

    @Test
    void getExchangeRate_unknownCurrencyReturnsNull() {
        service.removeCurrency(Currency.NEXUS);
        assertNull(service.getExchangeRate(Currency.NEXUS, Currency.USD));
    }

    @Test
    void setUsdValue_updatesRate() {
        service.setUsdValue(Currency.BTC, new BigDecimal("60000"));
        ExchangeRate rate = service.getExchangeRate(Currency.BTC, Currency.USD);
        assertEquals(0, new BigDecimal("60000").compareTo(rate.getRate()));
    }

    @Test
    void setUsdValue_nonPositiveThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> service.setUsdValue(Currency.BTC, BigDecimal.ZERO));
    }

    @Test
    void applySpread_reducesConvertedAmount() throws Exception {
        setField(service, "spreadBps", 50L); // 0.5%
        BigDecimal converted = service.convert(Currency.USDT, Currency.USDC, new BigDecimal("100"));
        // spread 50bps → 实际到手 99.5
        assertEquals(0, new BigDecimal("99.5").compareTo(converted));
    }

    @Test
    void getSupportedCurrencies_includesDefaults() {
        List<Currency> supported = service.getSupportedCurrencies();
        assertTrue(supported.contains(Currency.USD));
        assertTrue(supported.contains(Currency.BTC));
        assertTrue(supported.contains(Currency.NEXUS));
        assertEquals(8, supported.size());
    }

    @Test
    void getSupportedCurrencies_afterRemove() {
        service.removeCurrency(Currency.NEXUS);
        List<Currency> supported = service.getSupportedCurrencies();
        assertFalse(supported.contains(Currency.NEXUS));
        assertEquals(7, supported.size());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
