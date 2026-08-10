package org.nexus.oracle.price;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 价格预言机服务。
 *
 * <p>对外提供资产实时价格、历史价格与价格变更订阅能力。
 * 内部聚合多个 {@link PriceFeed} 数据源，做异常值剔除与置信度评估。
 */
public interface PriceOracle {

    /**
     * 获取资产当前聚合价格。
     *
     * @param asset 资产符号（如 "BTC" / "ETH" / "NEX"）
     * @return 价格条目
     */
    PriceEntry getPrice(String asset);

    /**
     * 订阅资产价格变更。
     *
     * @param asset    资产符号
     * @param callback 价格变更回调
     * @return 订阅句柄，可用于取消订阅
     */
    String subscribe(String asset, Consumer<PriceEntry> callback);

    /**
     * 取消订阅。
     *
     * @param subscriptionId 订阅句柄
     */
    void unsubscribe(String subscriptionId);

    /**
     * 获取资产在指定时间点的历史价格。
     *
     * @param asset 资产符号
     * @param time  时间点
     * @return 价格条目；若该时间点无数据则返回 {@link Optional#empty()}
     */
    Optional<PriceEntry> getHistoricalPrice(String asset, Instant time);
}