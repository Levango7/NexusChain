package org.nexus.oracle.price.feeds;

import lombok.extern.slf4j.Slf4j;
import org.nexus.oracle.price.PriceFeed;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Chainlink 链上预言机数据源适配器骨架。
 *
 * <p>通过 Chainlink Price Feed 合约读取链上聚合价格。当前为占位实现。
 */
@Slf4j
@Component
public class ChainlinkPriceFeed implements PriceFeed {

    @Override
    public String sourceName() {
        return "CHAINLINK";
    }

    @Override
    public BigDecimal fetch(String asset) {
        // TODO: 调用 Chainlink PriceFeed 合约 latestRoundData() 读取链上聚合价
        log.debug("ChainlinkPriceFeed.fetch skeleton invoked: asset={}", asset);
        return null;
    }

    @Override
    public boolean isAvailable() {
        // TODO: 检查链上预言机合约可达性 + 最新 round 数据新鲜度
        return false;
    }
}