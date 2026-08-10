package org.nexus.oracle.price;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * {@link PriceOracle} 多源聚合实现。
 *
 * <p>策略：
 * <ol>
 *   <li>并发拉取所有可用 {@link PriceFeed} 的报价</li>
 *   <li>剔除偏离中位数超过阈值（默认 20%）的异常源</li>
 *   <li>对剩余源取算术均值，按存活源数 / 总源数产出置信度</li>
 * </ol>
 *
 * <p>订阅通过定时调度器周期拉取并回调；历史价格维护进程内滚动窗口
 * （每资产最多保留 {@value #HISTORY_MAX} 条）。
 */
@Slf4j
@Service
public class AggregatedPriceOracle implements PriceOracle {

    /** 异常值剔除阈值：偏离中位数超过该比例（20%）的源被剔除 */
    private static final double DEVIATION_THRESHOLD = 0.20d;

    /** 每资产历史价格窗口上限 */
    private static final int HISTORY_MAX = 1000;

    /** 订阅拉取周期（秒） */
    private static final long SUBSCRIBE_INTERVAL_SECONDS = 5;

    /** 数据源列表，由配置注入 */
    private final List<PriceFeed> feeds;

    /** 历史价格存储：asset → 时间排序的价格条目列表 */
    private final Map<String, List<PriceEntry>> history = new ConcurrentHashMap<>();

    /** 订阅表：subscriptionId → 订阅任务 */
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /** 订阅调度器（懒启动） */
    private ScheduledExecutorService subscriptionScheduler;

    public AggregatedPriceOracle(List<PriceFeed> feeds) {
        this.feeds = feeds == null ? Collections.emptyList() : feeds;
    }

    @Override
    public PriceEntry getPrice(String asset) {
        if (asset == null || asset.isBlank()) {
            return emptyEntry(asset);
        }
        // 并发拉取各可用源的报价
        List<CompletableFuture<BigDecimal>> futures = new ArrayList<>();
        List<PriceFeed> availableFeeds = new ArrayList<>();
        for (PriceFeed feed : feeds) {
            if (!feed.isAvailable()) {
                continue;
            }
            availableFeeds.add(feed);
            futures.add(CompletableFuture.supplyAsync(() -> safeFetch(feed, asset)));
        }
        if (futures.isEmpty()) {
            log.debug("getPrice: no available feed for asset={}", asset);
            return emptyEntry(asset);
        }

        // 收集非空报价
        List<BigDecimal> quotes = futures.stream()
                .map(f -> f.orTimeout(6, TimeUnit.SECONDS).join())
                .filter(q -> q != null && q.compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());
        if (quotes.isEmpty()) {
            return emptyEntry(asset);
        }

        // 中位数
        List<BigDecimal> sorted = new ArrayList<>(quotes);
        Collections.sort(sorted);
        BigDecimal median = sorted.get(sorted.size() / 2);

        // 剔除偏离中位数超阈值的异常源
        List<BigDecimal> filtered = quotes.stream()
                .filter(q -> deviation(q, median).compareTo(BigDecimal.valueOf(DEVIATION_THRESHOLD)) <= 0)
                .collect(Collectors.toList());
        if (filtered.isEmpty()) {
            filtered = quotes;
        }

        // 算术均值
        BigDecimal sum = filtered.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = sum.divide(BigDecimal.valueOf(filtered.size()), 8, RoundingMode.HALF_UP);

        // 置信度 = 存活源数 / 总源数
        double confidence = (double) filtered.size() / feeds.size();

        PriceEntry entry = PriceEntry.builder()
                .asset(asset)
                .price(average)
                .timestamp(Instant.now())
                .source("AGGREGATED")
                .confidence(confidence)
                .build();
        appendHistory(asset, entry);
        return entry;
    }

    @Override
    public String subscribe(String asset, Consumer<PriceEntry> callback) {
        if (asset == null || callback == null) {
            return null;
        }
        ensureScheduler();
        String subscriptionId = "SUB-" + UUID.randomUUID().toString().replace("-", "");
        ScheduledFuture<?> task = subscriptionScheduler.scheduleAtFixedRate(() -> {
            try {
                PriceEntry entry = getPrice(asset);
                if (entry != null && entry.getPrice() != null
                        && entry.getPrice().compareTo(BigDecimal.ZERO) > 0) {
                    callback.accept(entry);
                }
            } catch (Exception e) {
                log.debug("Subscription callback failed: asset={}", asset, e);
            }
        }, 0, SUBSCRIBE_INTERVAL_SECONDS, TimeUnit.SECONDS);
        subscriptions.put(subscriptionId, new Subscription(asset, task));
        log.info("Price subscription created: id={}, asset={}", subscriptionId, asset);
        return subscriptionId;
    }

    @Override
    public void unsubscribe(String subscriptionId) {
        Subscription sub = subscriptions.remove(subscriptionId);
        if (sub != null) {
            sub.task.cancel(false);
            log.info("Price subscription cancelled: id={}, asset={}", subscriptionId, sub.asset);
        }
    }

    @Override
    public Optional<PriceEntry> getHistoricalPrice(String asset, Instant time) {
        if (asset == null || time == null) {
            return Optional.empty();
        }
        List<PriceEntry> entries = history.get(asset.toUpperCase());
        if (entries == null || entries.isEmpty()) {
            return Optional.empty();
        }
        // 取时间戳不晚于 time 的最新一条
        PriceEntry best = null;
        for (PriceEntry entry : entries) {
            if (!entry.getTimestamp().isAfter(time)
                    && (best == null || entry.getTimestamp().isAfter(best.getTimestamp()))) {
                best = entry;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 查询资产历史价格条数（测试 / 审计用）。 */
    public int historySize(String asset) {
        List<PriceEntry> entries = history.get(asset == null ? null : asset.toUpperCase());
        return entries == null ? 0 : entries.size();
    }

    private void appendHistory(String asset, PriceEntry entry) {
        List<PriceEntry> entries = history.computeIfAbsent(asset.toUpperCase(),
                k -> Collections.synchronizedList(new ArrayList<>()));
        entries.add(entry);
        while (entries.size() > HISTORY_MAX) {
            entries.remove(0);
        }
    }

    private BigDecimal safeFetch(PriceFeed feed, String asset) {
        try {
            return feed.fetch(asset);
        } catch (Exception e) {
            log.debug("Feed fetch failed: source={}, asset={}", feed.sourceName(), asset, e);
            return null;
        }
    }

    private BigDecimal deviation(BigDecimal quote, BigDecimal median) {
        if (median.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return quote.subtract(median).abs()
                .divide(median, 8, RoundingMode.HALF_UP);
    }

    private void ensureScheduler() {
        if (subscriptionScheduler == null) {
            synchronized (this) {
                if (subscriptionScheduler == null) {
                    subscriptionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "price-subscription");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
    }

    private PriceEntry emptyEntry(String asset) {
        return PriceEntry.builder()
                .asset(asset)
                .price(BigDecimal.ZERO)
                .timestamp(Instant.now())
                .source("AGGREGATED")
                .confidence(0.0)
                .build();
    }

    /** 订阅记录：资产 + 调度任务句柄 */
    private static final class Subscription {
        final String asset;
        final ScheduledFuture<?> task;

        Subscription(String asset, ScheduledFuture<?> task) {
            this.asset = asset;
            this.task = task;
        }
    }
}
