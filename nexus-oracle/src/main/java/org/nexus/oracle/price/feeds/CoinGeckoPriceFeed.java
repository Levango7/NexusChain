package org.nexus.oracle.price.feeds;

import lombok.extern.slf4j.Slf4j;
import org.nexus.oracle.price.PriceFeed;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * CoinGecko 聚合行情数据源适配器骨架。
 *
 * <p>通过 CoinGecko API 拉取多市场聚合价格。当前为占位实现。
 */
@Slf4j
@Component
public class CoinGeckoPriceFeed implements PriceFeed {

    @Override
    public String sourceName() {
        return "COINGECKO";
    }

    @Override
    public BigDecimal fetch(String asset) {
        // TODO: 调用 CoinGecko API（/api/v3/simple/price?ids=...&vs_currencies=usd）拉取聚合价
        log.debug("CoinGeckoPriceFeed.fetch skeleton invoked: asset={}", asset);
        return null;
    }

    @Override
    public boolean isAvailable() {
        // TODO: 探活 CoinGecko API（注意免费档限频）
        return false;
    }
}