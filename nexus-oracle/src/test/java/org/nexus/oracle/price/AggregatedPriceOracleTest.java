package org.nexus.oracle.price;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.oracle.price.feeds.BinancePriceFeed;
import org.nexus.oracle.price.feeds.ChainlinkPriceFeed;
import org.nexus.oracle.price.feeds.CoinGeckoPriceFeed;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AggregatedPriceOracle} 单元测试。
 */
class AggregatedPriceOracleTest {

    private BinancePriceFeed binance;
    private CoinGeckoPriceFeed coinGecko;
    private ChainlinkPriceFeed chainlink;
    private AggregatedPriceOracle oracle;

    @BeforeEach
    void setUp() {
        binance = new BinancePriceFeed();
        coinGecko = new CoinGeckoPriceFeed();
        chainlink = new ChainlinkPriceFeed();
        oracle = new AggregatedPriceOracle(List.of(binance, coinGecko, chainlink));
    }

    @Test
    void getPrice_allSourcesAgree_shouldReturnAverage() {
        binance.setStaticPrice("BTC", new BigDecimal("50000"));
        coinGecko.setStaticPrice("BTC", new BigDecimal("50000"));
        chainlink.setStaticPrice("BTC", new BigDecimal("50000"));

        PriceEntry entry = oracle.getPrice("BTC");

        assertEquals(0, new BigDecimal("50000").compareTo(entry.getPrice()));
        assertEquals("AGGREGATED", entry.getSource());
        assertEquals(1.0, entry.getConfidence(), 0.01);
    }

    @Test
    void getPrice_outlierSource_shouldBeFiltered() {
        binance.setStaticPrice("BTC", new BigDecimal("50000"));
        coinGecko.setStaticPrice("BTC", new BigDecimal("50050"));
        // 异常源：偏离中位数远超 20%
        chainlink.setStaticPrice("BTC", new BigDecimal("999999"));

        PriceEntry entry = oracle.getPrice("BTC");

        // 异常源被剔除后，均价应接近 50025 而非被拉高
        assertTrue(entry.getPrice().compareTo(new BigDecimal("60000")) < 0,
                "Outlier should be filtered, got: " + entry.getPrice());
        assertTrue(entry.getConfidence() < 1.0);
    }

    @Test
    void getPrice_noAvailableSource_shouldReturnZero() {
        // 未注入任何静态价格，三个源均不可用
        PriceEntry entry = oracle.getPrice("BTC");

        assertEquals(0, BigDecimal.ZERO.compareTo(entry.getPrice()));
        assertEquals(0.0, entry.getConfidence(), 0.01);
    }

    @Test
    void getHistoricalPrice_afterFetch_shouldReturn() {
        binance.setStaticPrice("ETH", new BigDecimal("3000"));
        coinGecko.setStaticPrice("ETH", new BigDecimal("3000"));
        chainlink.setStaticPrice("ETH", new BigDecimal("3000"));

        Instant afterFirst = Instant.now().plusSeconds(1);
        oracle.getPrice("ETH");

        Optional<PriceEntry> historical = oracle.getHistoricalPrice("ETH", afterFirst);

        assertTrue(historical.isPresent());
        assertEquals(0, new BigDecimal("3000").compareTo(historical.get().getPrice()));
    }

    @Test
    void getHistoricalPrice_noData_shouldBeEmpty() {
        Optional<PriceEntry> historical = oracle.getHistoricalPrice("UNKNOWN", Instant.now());
        assertTrue(historical.isEmpty());
    }

    @Test
    void subscribeAndUnsubscribe_shouldCallbackAndStop() throws Exception {
        binance.setStaticPrice("BTC", new BigDecimal("50000"));
        coinGecko.setStaticPrice("BTC", new BigDecimal("50000"));
        chainlink.setStaticPrice("BTC", new BigDecimal("50000"));

        AtomicReference<PriceEntry> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        String subscriptionId = oracle.subscribe("BTC", entry -> {
            received.set(entry);
            latch.countDown();
        });

        assertNotNull(subscriptionId);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "Subscription callback should fire");
        assertEquals(0, new BigDecimal("50000").compareTo(received.get().getPrice()));

        // 取消订阅不应抛异常
        oracle.unsubscribe(subscriptionId);
    }
}
