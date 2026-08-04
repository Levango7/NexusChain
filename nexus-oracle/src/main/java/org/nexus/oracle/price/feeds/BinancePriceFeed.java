package org.nexus.oracle.price.feeds;

import lombok.extern.slf4j.Slf4j;
import org.nexus.oracle.price.PriceFeed;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Binance 行情数据源适配器骨架。
 *
 * <p>通过 Binance REST API 拉取最新成交价。当前为占位实现。
 */
@Slf4j
@Component
public class BinancePriceFeed implements PriceFeed {

    @Override
    public String sourceName() {
        return "BINANCE";
    }

    @Override
    public BigDecimal fetch(String asset) {
        // TODO: 调用 Binance REST API（/api/v3/ticker/price?symbol=...）拉取最新价
        log.debug("BinancePriceFeed.fetch skeleton invoked: asset={}", asset);
        return null;
    }

    @Override
    public boolean isAvailable() {
        // TODO: 探活 Binance API（带超时 + 限频）
        return false;
    }
}