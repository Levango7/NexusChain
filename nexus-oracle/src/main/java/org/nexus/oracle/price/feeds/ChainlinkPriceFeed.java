package org.nexus.oracle.price.feeds;

import lombok.extern.slf4j.Slf4j;
import org.nexus.oracle.price.PriceFeed;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chainlink 链上预言机数据源适配器。
 *
 * <p>生产实现应通过 Chainlink Price Feed 合约的 {@code latestRoundData()}
 * 读取链上聚合价格。当前链客户端尚未接入，提供可注入报价机制
 * （{@link #setStaticPrice}），由链上同步任务或测试注入价格；
 * 未注入任何价格时数据源视为不可用。
 */
@Slf4j
@Component
public class ChainlinkPriceFeed implements PriceFeed {

    /** 注入价格表（asset → price），链上同步 / 测试用 */
    private final Map<String, BigDecimal> prices = new ConcurrentHashMap<>();

    @Override
    public String sourceName() {
        return "CHAINLINK";
    }

    @Override
    public BigDecimal fetch(String asset) {
        if (asset == null || asset.isBlank()) {
            return null;
        }
        BigDecimal price = prices.get(asset.toUpperCase(Locale.ROOT));
        if (price != null) {
            log.debug("Chainlink price fetched: asset={}, price={}", asset, price);
        }
        return price;
    }

    @Override
    public boolean isAvailable() {
        // 有注入价格即视为链上数据源可用（生产应改为探测合约可达性 + round 新鲜度）
        return !prices.isEmpty();
    }

    /**
     * 注入链上聚合价格（由链上同步任务或测试调用）。
     *
     * @param asset 资产符号
     * @param price 价格
     */
    public void setStaticPrice(String asset, BigDecimal price) {
        if (asset != null && price != null) {
            prices.put(asset.toUpperCase(Locale.ROOT), price);
        }
    }

    /** 清空注入价格。 */
    public void clearStaticPrices() {
        prices.clear();
    }
}
