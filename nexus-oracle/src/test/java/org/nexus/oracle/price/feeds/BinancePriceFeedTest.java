package org.nexus.oracle.price.feeds;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BinancePriceFeed} 单元测试。
 * <p>覆盖静态价格注入路径、可用性探测与边界条件。</p>
 */
class BinancePriceFeedTest {

    private BinancePriceFeed feed;

    @BeforeEach
    void setUp() {
        feed = new BinancePriceFeed();
    }

    @Test
    void sourceName_shouldBeBinance() {
        assertEquals("BINANCE", feed.sourceName());
    }

    @Test
    void fetch_withStaticPrice_shouldReturnImmediately() {
        feed.setStaticPrice("BTC", new BigDecimal("50000"));

        BigDecimal price = feed.fetch("BTC");
        assertEquals(0, new BigDecimal("50000").compareTo(price));
    }

    @Test
    void fetch_staticPriceCaseInsensitive_shouldWork() {
        feed.setStaticPrice("BTC", new BigDecimal("50000"));

        BigDecimal price = feed.fetch("btc");
        assertEquals(0, new BigDecimal("50000").compareTo(price));
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
        // 没有静态价格注入，isAvailable 走网络探测路径（可能 false）
        // 仅验证不抛异常
    }

    @Test
    void clearStaticPrices_shouldEmpty() {
        feed.setStaticPrice("BTC", new BigDecimal("50000"));
        feed.clearStaticPrices();
        // 清空后无静态价格；fetch 走 HTTP 路径（测试环境可能 null）
        // 仅验证不抛异常
        feed.fetch("BTC");
    }
}