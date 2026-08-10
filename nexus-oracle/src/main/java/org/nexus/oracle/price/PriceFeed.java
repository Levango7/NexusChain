package org.nexus.oracle.price;

import java.math.BigDecimal;

/**
 * 价格数据源接口。
 *
 * <p>每个实现对应一个外部数据源（中心化交易所 / 聚合器 / 链上预言机），
 * 由 {@link PriceOracle} 聚合后产出最终价格。
 */
public interface PriceFeed {

    /**
     * 数据源标识（如 "BINANCE" / "COINGECKO" / "CHAINLINK"）。
     *
     * @return 数据源名
     */
    String sourceName();

    /**
     * 拉取资产当前价格。
     *
     * @param asset 资产符号
     * @return 当前价格；数据源不可用或未覆盖该资产时返回 {@code null}
     */
    BigDecimal fetch(String asset);

    /**
     * 该数据源是否当前可用（健康检查）。
     *
     * @return true 表示可正常拉取
     */
    boolean isAvailable();
}