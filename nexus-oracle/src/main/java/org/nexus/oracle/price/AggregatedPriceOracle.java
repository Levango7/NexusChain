package org.nexus.oracle.price;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@link PriceOracle} 多源聚合骨架实现。
 *
 * <p>策略：
 * <ol>
 *   <li>并发拉取所有可用 {@link PriceFeed} 的报价</li>
 *   <li>剔除中位数偏离超过阈值的异常源</li>
 *   <li>对剩余源取加权均值，按源一致性产出置信度</li>
 * </ol>
 *
 * <p>当前为占位实现，仅返回空结果。后续注入 {@code List<PriceFeed>} 后填充聚合逻辑。
 */
@Slf4j
@Service
public class AggregatedPriceOracle implements PriceOracle {

    /** 数据源列表，由配置注入 */
    private final List<PriceFeed> feeds;

    public AggregatedPriceOracle(List<PriceFeed> feeds) {
        this.feeds = feeds == null ? Collections.emptyList() : feeds;
    }

    @Override
    public PriceEntry getPrice(String asset) {
        // TODO: 并发拉取各源报价 → 异常值剔除 → 加权聚合 → 产出置信度
        log.debug("getPrice skeleton invoked: asset={}, feedCount={}", asset, feeds.size());
        return PriceEntry.builder()
                .asset(asset)
                .price(BigDecimal.ZERO)
                .timestamp(Instant.now())
                .source("AGGREGATED")
                .confidence(0.0)
                .build();
    }

    @Override
    public String subscribe(String asset, Consumer<PriceEntry> callback) {
        // TODO: 注册订阅，启动定时拉取任务，价格变更时回调
        log.debug("subscribe skeleton invoked: asset={}", asset);
        return null;
    }

    @Override
    public void unsubscribe(String subscriptionId) {
        // TODO: 取消订阅并停止对应拉取任务
        log.debug("unsubscribe skeleton invoked: subscriptionId={}", subscriptionId);
    }

    @Override
    public Optional<PriceEntry> getHistoricalPrice(String asset, Instant time) {
        // TODO: 查询历史价格存储
        log.debug("getHistoricalPrice skeleton invoked: asset={}, time={}", asset, time);
        return Optional.empty();
    }
}