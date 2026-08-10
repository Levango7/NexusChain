package org.nexus.oracle.price.feeds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CoinGeckoPriceFeed} 单元测试。
 */
class CoinGeckoPriceFeedTest {

    private CoinGeckoPriceFeed feed;

    @BeforeEach
    void setUp() {
        feed = new CoinGeckoPriceFeed();
    }

    @Test
    void sourceName_shouldBeCoinGecko() {
        assertEquals("COINGECKO", feed.sourceName());
    }

    @Test
    void fetch_withStaticPrice_shouldReturnImmediately() {
        feed.setStaticPrice("BTC", new BigDecimal("50000"));

        BigDecimal price = feed.fetch("BTC");
        assertEquals(0, new BigDecimal("50000").compareTo(price));
    }

    @Test
    void fetch_staticPriceCaseInsensitive_shouldWork() {
        feed.setStaticPrice("ETH", new BigDecimal("3000"));

        BigDecimal price = feed.fetch("eth");
        assertEquals(0, new BigDecimal("3000").compareTo(price));
    }

    @Test
    void fetch_nullAsset_shouldReturnNull() {
        assertNull(feed.fetch(null));
    }

    @Test
    void fetch_blankAsset_shouldReturnNull() {
        assertNull(feed.fetch(""));
        assertNull(feed.fetch("   "));
    }

    @Test
    void isAvailable_withStaticPrice_shouldBeTrue() {
        feed.setStaticPrice("BTC", new BigDecimal("50000"));
        assertTrue(feed.isAvailable());
    }

    @Test
    void setStaticPrice_nullArgs_shouldBeNoOp() {
        feed.setStaticPrice(null, BigDecimal.TEN);
        feed.setStaticPrice("BTC", null);
    }

    @Test
    void clearStaticPrices_shouldEmpty() {
        feed.setStaticPrice("BTC", new BigDecimal("50000"));
        feed.clearStaticPrices();
        feed.fetch("BTC");
    }
}