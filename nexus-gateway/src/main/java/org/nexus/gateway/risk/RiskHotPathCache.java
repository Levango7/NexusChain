package org.nexus.gateway.risk;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 风控热路径短 TTL 缓存（2026-10-03 毫秒级改造）。
 *
 * <p><b>背景</b>：每次支付的风控评估原路径打 3 次 DB（RiskProfile 查询 + 日/月限额
 * {@code SUM} 聚合）。SUM 是 O(商户订单量) 的工作，订单量增长后单次评估退化到几十毫秒
 * 以上——这是「毫秒级」目标的真正敌人（规则链本身是内存 O(rules)，不是瓶颈）。</p>
 *
 * <p><b>设计</b>：按 key 缓存值，带时间戳；过期即穿透回源一次后刷新。
 * <ul>
 *   <li><b>语义零变化</b>：回源函数就是原来的查询，缓存只是复用近窗结果——
 *       数据口径（PAID/PAYING 状态集）与 {@code sumMerchantAmountSince} 完全一致；</li>
 *   <li><b>时延换实时</b>：限额校验最多滞后一个 TTL 窗（日/月限额 5s、画像 30s），
 *       突发流量理论上可在一个窗内超限——对「限额」这个粗粒度闸门而言，
 *       5s 的有界滞后可接受（精确到笔的严格计数器方案见
 *       {@code DefaultPaymentRiskService} 头注的后续项）；</li>
 *   <li><b>无界 key 风险</b>：key 为 merchantId（Long，有限集）+ 固定后缀，
 *       不存在缓存膨胀；条目在滚动窗口语义下自然覆盖，无需主动驱逐。</li>
 * </ul></p>
 *
 * <p>线程安全：{@link ConcurrentHashMap} + 原子 {@code compute}；回源查询在 compute
 * 内串行化（同一 key 的并发回源最多一查，其余线程短暂等待后拿新值——雷群回源
 * 被自然合并）。</p>
 */
class RiskHotPathCache<V> {

    private record Entry<V>(V value, long expiresAtMillis) {
    }

    private final Map<String, Entry<V>> cache = new ConcurrentHashMap<>();
    private final long ttlMillis;

    RiskHotPathCache(long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
        this.ttlMillis = ttlMillis;
    }

    /**
     * 读取：未过期直接返回缓存；过期则回源一次并刷新（并发同 key 只回源一次）。
     *
     * @param key     缓存键（调用方保证有限集）
     * @param loader  回源函数（与无缓存路径完全相同的查询）
     */
    V get(String key, Supplier<V> loader) {
        long now = System.currentTimeMillis();
        Entry<V> entry = cache.get(key);
        if (entry != null && now < entry.expiresAtMillis()) {
            return entry.value();
        }
        // compute 保证同 key 并发回源只执行一次；loader 异常会原样抛出且不缓存
        return cache.compute(key, (k, old) -> {
            // 双检：等锁期间别的线程可能已刷新
            if (old != null && System.currentTimeMillis() < old.expiresAtMillis()) {
                return old;
            }
            return new Entry<>(loader.get(), System.currentTimeMillis() + ttlMillis);
        }).value();
    }

    /** 当前缓存条目数（测试/诊断用）。 */
    int size() {
        return cache.size();
    }
}
