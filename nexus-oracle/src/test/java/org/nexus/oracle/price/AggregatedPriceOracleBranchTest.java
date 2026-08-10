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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AggregatedPriceOracle} 补充分支测试：覆盖 null / blank asset、null callback、
 * unsubscribe unknown、historySize、empty feeds 构造、getHistoricalPrice null 入参等。
 */
class AggregatedPriceOracleBranchTest {

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
    void getPrice_nullAsset_shouldReturnEmptyEntry() {
        PriceEntry e = oracle.getPrice(null);
        assertNotNull(e);
        assertEquals(0, BigDecimal.ZERO.compareTo(e.getPrice()));
        assertEquals(0.0, e.getConfidence(), 0.001);
    }

    @Test
    void getPrice_blankAsset_shouldReturnEmptyEntry() {
        PriceEntry e = oracle.getPrice("   ");
        assertNotNull(e);
        assertEquals(0, BigDecimal.ZERO.compareTo(e.getPrice()));
    }

    @Test
    void getPrice_someFeedsUnavailable_shouldUseAvailableOnly() {
        // 仅 binance 注入价格，其他源不可用
        binance.setStaticPrice("BTC", new BigDecimal("50000"));

        PriceEntry e = oracle.getPrice("BTC");
        assertEquals(0, new BigDecimal("50000").compareTo(e.getPrice()));
        // 置信度 = 1/3
        assertEquals(1.0 / 3.0, e.getConfidence(), 0.01);
    }

    @Test
    void getPrice_allFeedsReturnZeroOrNegative_shouldReturnEmpty() {
        // 静态注入 0 价格（应被过滤）
        binance.setStaticPrice("BTC", BigDecimal.ZERO);
        coinGecko.setStaticPrice("BTC", new BigDecimal("-1"));
        chainlink.setStaticPrice("BTC", BigDecimal.ZERO);

        PriceEntry e = oracle.getPrice("BTC");
        assertEquals(0, BigDecimal.ZERO.compareTo(e.getPrice()));
    }

    @Test
    void subscribe_nullAsset_shouldReturnNull() {
        assertNull(oracle.subscribe(null, entry -> {}));
    }

    @Test
    void subscribe_nullCallback_shouldReturnNull() {
        assertNull(oracle.subscribe("BTC", null));
    }

    @Test
    void unsubscribe_unknownId_shouldBeNoOp() {
        // 不应抛异常
        oracle.unsubscribe("NOPE");

    }

    @Test
    void getHistoricalPrice_nullArgs_shouldReturnEmpty() {
        assertTrue(oracle.getHistoricalPrice(null, Instant.now()).isEmpty());
        assertTrue(oracle.getHistoricalPrice("BTC", null).isEmpty());
    }

    @Test
    void getHistoricalPrice_beforeFirstEntry_shouldReturnEmpty() {
        binance.setStaticPrice("BTC", new BigDecimal("50000"));
        Instant before = Instant.now().minusSeconds(60);
        oracle.getPrice("BTC");

        Optional<PriceEntry> h = oracle.getHistoricalPrice("BTC", before);
        assertTrue(h.isEmpty());
    }

    @Test
    void getHistoricalPrice_atEntryTimestamp_shouldReturnEntry() {
        binance.setStaticPrice("BTC", new BigDecimal("50000"));
        oracle.getPrice("BTC");
        Instant after = Instant.now().plusSeconds(1);

        Optional<PriceEntry> h = oracle.getHistoricalPrice("BTC", after);
        assertTrue(h.isPresent());
    }

    @Test
    void getHistoricalPrice_caseInsensitiveAsset_shouldWork() {
        binance.setStaticPrice("BTC", new BigDecimal("50000"));
        oracle.getPrice("BTC");
        Instant after = Instant.now().plusSeconds(1);

        // 历史键以大写存储，小写查询应也能命中
        Optional<PriceEntry> h = oracle.getHistoricalPrice("btc", after);
        assertTrue(h.isPresent());
    }

    @Test
    void historySize_shouldReturnCount() {
        binance.setStaticPrice("BTC", new BigDecimal("50000"));
        assertEquals(0, oracle.historySize("BTC"));

        oracle.getPrice("BTC");
        assertEquals(1, oracle.historySize("BTC"));

        oracle.getPrice("BTC");
        assertEquals(2, oracle.historySize("BTC"));
    }

    @Test

    void historySize_unknownAsset_shouldReturnZero() {
        assertEquals(0, oracle.historySize("NOPE"));
    }

    @Test
    void constructor_nullFeeds_shouldNotThrow() {
        AggregatedPriceOracle empty = new AggregatedPriceOracle(null);
        PriceEntry e = empty.getPrice("BTC");
        assertNotNull(e);
        assertEquals(0, BigDecimal.ZERO.compareTo(e.getPrice()));
    }
}