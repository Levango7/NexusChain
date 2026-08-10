package org.nexus.oracle.price.feeds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ChainlinkPriceFeed} 单元测试。
 */
class ChainlinkPriceFeedTest {

    private ChainlinkPriceFeed feed;

    @BeforeEach
    void setUp() {
        feed = new ChainlinkPriceFeed();
    }

    @Test
    void sourceName_shouldBeChainlink() {
        assertEquals("CHAINLINK", feed.sourceName());
    }

    @Test
    void fetch_withStaticPrice_shouldReturnPrice() {
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
    void fetch_unknownAsset_shouldReturnNull() {
        // 未注入该资产价格
        assertNull(feed.fetch("DOGE"));
    }

    @Test
    void isAvailable_emptyPrices_shouldBeFalse() {
        assertFalse(feed.isAvailable());
    }

    @Test
    void isAvailable_withPrices_shouldBeTrue() {
        feed.setStaticPrice("BTC", new BigDecimal("50000"));
        assertTrue(feed.isAvailable());
    }

    @Test
    void setStaticPrice_nullArgs_shouldBeNoOp() {
        feed.setStaticPrice(null, BigDecimal.TEN);
        feed.setStaticPrice("BTC", null);
        assertFalse(feed.isAvailable());
    }

    @Test
    void clearStaticPrices_shouldEmpty() {
        feed.setStaticPrice("BTC", new BigDecimal("50000"));
        feed.clearStaticPrices();
        assertFalse(feed.isAvailable());
        assertNull(feed.fetch("BTC"));
    }
}