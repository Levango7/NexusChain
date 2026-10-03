package org.nexus.gateway.orchestration.routing.strategy;

import org.nexus.gateway.config.RoutingWave16Properties;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.routing.RoutingContext;
import org.nexus.gateway.orchestration.routing.RoutingJsonCodec;
import org.nexus.gateway.orchestration.routing.ai.MetricsCollector;
import org.nexus.gateway.orchestration.routing.health.ChannelHealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多目标路由策略服务（Wave 16 模块一）。
 *
 * <p>为 {@code RoutingStrategy.MULTI_OBJECTIVE} 计算各候选 connector 的综合评分：
 * {@code total = wCost*cost + wSuccess*success + wLatency*latency + wRisk*risk}，
 * 并按评分降序给出 connector 顺序。</p>
 *
 * <p>权重来源（按优先级）：</p>
 * <ol>
 *   <li>DB 策略配置（{@code routing_strategy_configs}）：priority 降序取第一条
 *       条件命中的 enabled 配置（热加载，间隔 {@code nexus.routing.strategy.hot-reload-interval}）；</li>
 *   <li>未命中 → {@code nexus.routing.strategy.default-*-weight} 默认权重。</li>
 * </ol>
 *
 * <p>各维度归一化（均为 [0,1]，越大越好）：</p>
 * <ul>
 *   <li>cost：候选间费率线性归一（最低 1.0，最高 0.0；全部相等 → 1.0）；
 *       无 MetricsCollector 数据时按 {@code feeBasisPoints()} 归一；</li>
 *   <li>success：{@code ConnectorMetrics.successRate()}；无样本 → 0.5（中性）；</li>
 *   <li>latency：候选间平均延迟线性归一（最低延迟 1.0）；无样本 → 0.5（中性）；</li>
 *   <li>risk：{@link ChannelHealthService} 的 errorRateScore；未启用 → 0.5（中性）。</li>
 * </ul>
 *
 * <p>画像修正（模块六 {@code ResolvedRoutingProfile} 非空时）：排除
 * {@code excludedConnectors}（全被排除则放弃修正），{@code preferredConnectors}
 * 加 0.05 固定加成。</p>
 *
 * <p>等价性保证：{@code nexus.routing.strategy.enabled=false} 或候选 ≤ 1 时原样返回，
 * 与 Wave 15 行为一致。</p>
 */
@Service
public class MultiObjectiveRoutingService {

    private static final Logger log = LoggerFactory.getLogger(MultiObjectiveRoutingService.class);

    /** 画像偏好 connector 的固定评分加成。 */
    static final double PREFERRED_BONUS = 0.05;

    /** 目标权重（已归一化，sum=1）。 */
    public record ObjectiveWeights(double cost, double successRate, double latency, double risk) {
    }

    /**
     * 多目标决策结果：各候选总分（按有序）与降序 connector id 列表。
     */
    public record MultiObjectiveDecision(LinkedHashMap<String, Double> scores, List<String> orderedIds) {
    }

    /** 热加载快照条目：权重 + 适用条件。 */
    private record ResolvedConfig(ObjectiveWeights weights, Map<String, String> conditions) {
    }

    /**
     * 规则级固定配置的缓存条目（2026-10-03 A4）。config 为 null 表示
     * 「不可用」（缺失/禁用/权重非法）——负结果同样缓存一个 TTL，
     * 防止坏引用导致每笔 MULTI_OBJECTIVE 支付都打一次 findById。
     */
    private record PinnedEntry(ResolvedConfig config, long expiresAtMillis) {
    }

    private final RoutingStrategyConfigRepository repository;
    private final RoutingWave16Properties properties;

    /** 可选协作方（测试/未启用时可缺省，全部 null 安全）。 */
    private MetricsCollector metricsCollector;
    private ChannelHealthService channelHealthService;

    private volatile List<ResolvedConfig> snapshot = List.of();
    private volatile long lastRefreshNanos = 0L;
    private final Object refreshLock = new Object();
    /** 规则级固定配置缓存：configId → PinnedEntry（TTL 与快照链一致；读锁外，写锁内）。 */
    private final Map<Long, PinnedEntry> pinnedCache = new ConcurrentHashMap<>();

    @Autowired
    public MultiObjectiveRoutingService(RoutingStrategyConfigRepository repository,
                                        RoutingWave16Properties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Autowired(required = false)
    public void setMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    @Autowired(required = false)
    public void setChannelHealthService(ChannelHealthService channelHealthService) {
        this.channelHealthService = channelHealthService;
    }

    // ==================== 决策 ====================

    /**
     * 计算多目标决策。
     *
     * @param ctx        路由上下文（amount 单位与 RoutingRule.conditions 相同：分）
     * @param candidates 已筛选的候选 connector（非空）
     * @return 评分明细与降序结果；策略未启用或候选 ≤ 1 时 scores 为空、orderedIds 为原顺序
     */
    public MultiObjectiveDecision decide(RoutingContext ctx, List<PaymentConnector> candidates) {
        return decide(ctx, candidates, null);
    }

    /**
     * 多目标决策（规则级固定权重版，2026-10-03 A4）。
     *
     * @param strategyConfigId 规则钉死的 routing_strategy_configs.id；null = 走全局
     *                          「条件+priority」解析链（既有行为）
     */
    public MultiObjectiveDecision decide(RoutingContext ctx, List<PaymentConnector> candidates,
                                         Long strategyConfigId) {
        if (!properties.getStrategy().isEnabled() || candidates.size() <= 1) {
            List<String> ids = candidates.stream().map(PaymentConnector::getId).toList();
            return new MultiObjectiveDecision(new LinkedHashMap<>(), ids);
        }

        List<PaymentConnector> adjusted = applyProfileAdjustments(ctx, candidates);
        ObjectiveWeights weights = resolveWeights(ctx, strategyConfigId);
        LinkedHashMap<String, Double> totals = new LinkedHashMap<>();
        for (PaymentConnector c : adjusted) {
            totals.put(c.getId(), scoreCandidate(c, adjusted, weights));
        }
        // 画像偏好加成：评分归一后再叠加固定 bonus，偏好 connector 优先级提升
        if (ctx != null && ctx.profile() instanceof org.nexus.gateway.orchestration.routing.profile.ResolvedRoutingProfile profile) {
            for (String id : profile.preferredConnectors()) {
                totals.computeIfPresent(id, (k, v) -> v + PREFERRED_BONUS);
            }
        }
        List<String> ordered = new ArrayList<>(totals.keySet());
        ordered.sort((a, b) -> {
            int byScore = Double.compare(totals.get(b), totals.get(a));
            return byScore != 0 ? byScore : Integer.compare(indexOf(adjusted, a), indexOf(adjusted, b));
        });
        return new MultiObjectiveDecision(totals, List.copyOf(ordered));
    }

    /** 解析生效权重（全局链 → 默认权重）。 */
    public ObjectiveWeights resolveWeights(RoutingContext ctx) {
        return resolveWeights(ctx, null);
    }

    /**
     * 解析生效权重（规则级固定版，2026-10-03 A4）。
     * strategyConfigId 非空且该配置可用（存在/enabled/权重合法）→ 固定用之，
     * 绕过条件链；不可用 → 告警并回退全局链（诚实降级，不让坏引用炸路由）。
     */
    public ObjectiveWeights resolveWeights(RoutingContext ctx, Long strategyConfigId) {
        if (strategyConfigId != null) {
            ResolvedConfig pinned = pinnedConfig(strategyConfigId);
            if (pinned != null) {
                return pinned.weights();
            }
            log.warn("Pinned strategy config {} unusable (missing/disabled/invalid weights), "
                    + "falling back to global chain", strategyConfigId);
        }
        for (ResolvedConfig config : snapshot()) {
            if (matches(config.conditions(), ctx)) {
                return config.weights();
            }
        }
        return defaultWeights();
    }

    /**
     * 规则级固定配置解析（TTL 缓存，负结果也缓存——坏引用不至于每笔支付打一次 DB）。
     * 双检模式与快照链一致：锁外读 + 锁内刷新，同 configId 并发回源合并为一次。
     */
    private ResolvedConfig pinnedConfig(Long configId) {
        long ttl = ttlMillis();
        long now = System.currentTimeMillis();
        PinnedEntry entry = pinnedCache.get(configId);
        if (entry != null && now < entry.expiresAtMillis()) {
            return entry.config();
        }
        synchronized (refreshLock) {
            entry = pinnedCache.get(configId);
            now = System.currentTimeMillis();
            if (entry != null && now < entry.expiresAtMillis()) {
                return entry.config();
            }
            ResolvedConfig resolved = null;
            try {
                resolved = repository.findById(configId)
                        .filter(RoutingStrategyConfig::isEnabled)
                        .map(config -> {
                            ObjectiveWeights weights = parseWeights(config.getObjectiveWeightsJson());
                            return weights == null ? null
                                    : new ResolvedConfig(weights,
                                            RoutingJsonCodec.fromJsonToMap(config.getConditionsJson()));
                        })
                        .orElse(null);
            } catch (RuntimeException e) {
                log.warn("Pinned strategy config {} load failed, degrading to chain: {}",
                        configId, e.getMessage());
            }
            pinnedCache.put(configId, new PinnedEntry(resolved, System.currentTimeMillis() + ttl));
            return resolved;
        }
    }

    private long ttlMillis() {
        return parseTtl(properties.getStrategy().getHotReloadInterval()).toMillis();
    }

    // ==================== 评分内部 ====================

    private double scoreCandidate(PaymentConnector target, List<PaymentConnector> candidates,
                                  ObjectiveWeights w) {
        int fee = Math.max(0, target.feeBasisPoints());
        double costScore = costScore(fee, candidates);
        double successScore = 0.5;
        double latencyScore = 0.5;
        long latencyMs = 0L;
        if (metricsCollector != null) {
            var m = metricsCollector.metrics(target.getId());
            if (m != null && m.samples() > 0) {
                successScore = m.successRate();
                latencyMs = m.avgLatencyMs();
                latencyScore = latencyScore(latencyMs, candidates);
            } else {
                latencyScore = 0.5;
            }
        }
        double riskScore = 0.5;
        if (channelHealthService != null) {
            try {
                riskScore = channelHealthService.errorRateScore(target.getId());
            } catch (RuntimeException e) {
                log.debug("Health score unavailable for {}: {}", target.getId(), e.getMessage());
            }
        }
        return w.cost() * costScore + w.successRate() * successScore
                + w.latency() * latencyScore + w.risk() * riskScore;
    }

    /** 成本维度：费率最低 → 1.0，最高 → 0.0；区间为 0（全部相等）→ 1.0。 */
    private double costScore(int fee, List<PaymentConnector> candidates) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (PaymentConnector c : candidates) {
            int f = Math.max(0, c.feeBasisPoints());
            min = Math.min(min, f);
            max = Math.max(max, f);
        }
        if (max <= min) return 1.0;
        double normalized = (max - fee) / (double) (max - min);
        return Math.max(0.0, Math.min(1.0, normalized));
    }

    /** 延迟维度：平均延迟最低 → 1.0；区间为 0 → 1.0。 */
    private double latencyScore(long latencyMs, List<PaymentConnector> candidates) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (PaymentConnector c : candidates) {
            var m = metricsCollector.metrics(c.getId());
            if (m == null || m.samples() <= 0) continue;
            min = Math.min(min, m.avgLatencyMs());
            max = Math.max(max, m.avgLatencyMs());
        }
        if (max <= min) return 1.0;
        double normalized = (max - latencyMs) / (double) (max - min);
        return Math.max(0.0, Math.min(1.0, normalized));
    }

    /** 画像修正：排除 disabled connector，偏好 connector 加成。 */
    private List<PaymentConnector> applyProfileAdjustments(RoutingContext ctx,
                                                           List<PaymentConnector> candidates) {
        Object profileObj = ctx == null ? null : ctx.profile();
        if (!(profileObj instanceof org.nexus.gateway.orchestration.routing.profile.ResolvedRoutingProfile profile)) {
            return candidates;
        }
        Set<String> excluded = new HashSet<>(profile.excludedConnectors());
        List<PaymentConnector> filtered = candidates.stream()
                .filter(c -> !excluded.contains(c.getId()))
                .toList();
        if (filtered.isEmpty()) {
            log.debug("Profile excluded all candidates, ignoring exclusions");
            filtered = candidates;
        }
        // 偏好 connector 不在此处过滤/重排，由 decide() 在评分后叠加 PREFERRED_BONUS
        return filtered;
    }

    // ==================== 热加载 ====================

    private List<ResolvedConfig> snapshot() {
        Duration ttl = parseTtl(properties.getStrategy().getHotReloadInterval());
        long now = System.nanoTime();
        if (now - lastRefreshNanos >= ttl.toNanos()) {
            synchronized (refreshLock) {
                if (System.nanoTime() - lastRefreshNanos >= ttl.toNanos()) {
                    refreshSnapshot();
                    lastRefreshNanos = System.nanoTime();
                }
            }
        }
        return snapshot;
    }

    /** 强制刷新（配置变更后可手动触发；同时清规则级固定配置缓存——引用方立即看到新权重）。 */
    public void evictCache() {
        synchronized (refreshLock) {
            refreshSnapshot();
            pinnedCache.clear();
            lastRefreshNanos = System.nanoTime();
        }
    }

    private void refreshSnapshot() {
        try {
            List<ResolvedConfig> fresh = new ArrayList<>();
            for (RoutingStrategyConfig config : repository.findByEnabledTrueOrderByPriorityDesc()) {
                ObjectiveWeights weights = parseWeights(config.getObjectiveWeightsJson());
                if (weights == null) {
                    log.warn("Skip strategy config {} (invalid weights json)", config.getName());
                    continue;
                }
                fresh.add(new ResolvedConfig(weights, RoutingJsonCodec.fromJsonToMap(config.getConditionsJson())));
            }
            snapshot = List.copyOf(fresh);
            log.debug("Strategy config snapshot refreshed: {} configs", fresh.size());
        } catch (RuntimeException e) {
            log.warn("Strategy config hot-reload failed, keeping previous snapshot: {}", e.getMessage());
        }
    }

    private Duration parseTtl(String value) {
        try {
            return DurationStyle.detectAndParse(value);
        } catch (RuntimeException e) {
            log.warn("Invalid hot-reload interval '{}', falling back to 5s", value);
            return Duration.ofSeconds(5);
        }
    }

    // ==================== 权重解析 ====================

    private ObjectiveWeights defaultWeights() {
        RoutingWave16Properties.Strategy s = properties.getStrategy();
        return normalize(s.getDefaultCostWeight(), s.getDefaultSuccessRateWeight(),
                s.getDefaultLatencyWeight(), s.getDefaultRiskWeight());
    }

    /**
     * 解析权重 JSON；非法（null/空/无可识别键）时返回 null（调用方跳过该配置）。
     * 缺失的键取默认权重值；负数视为 0；最终归一化到 sum=1。
     */
    ObjectiveWeights parseWeights(String json) {
        Map<String, String> raw;
        try {
            raw = RoutingJsonCodec.fromJsonToMap(json);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (raw.isEmpty()) return null;
        // 至少要有一个可识别的目标键，否则视为非法配置（跳过该条）
        boolean recognized = raw.keySet().stream()
                .anyMatch(k -> "COST".equalsIgnoreCase(k) || "SUCCESS_RATE".equalsIgnoreCase(k)
                        || "LATENCY".equalsIgnoreCase(k) || "RISK".equalsIgnoreCase(k));
        if (!recognized) return null;
        RoutingWave16Properties.Strategy s = properties.getStrategy();
        double cost = extractWeight(raw, "COST", s.getDefaultCostWeight());
        double success = extractWeight(raw, "SUCCESS_RATE", s.getDefaultSuccessRateWeight());
        double latency = extractWeight(raw, "LATENCY", s.getDefaultLatencyWeight());
        double risk = extractWeight(raw, "RISK", s.getDefaultRiskWeight());
        return normalize(cost, success, latency, risk);
    }

    private double extractWeight(Map<String, String> raw, String key, double fallback) {
        String v = raw.get(key);
        if (v == null) {
            // 兼容小写键
            for (Map.Entry<String, String> e : raw.entrySet()) {
                if (key.equalsIgnoreCase(e.getKey())) {
                    v = e.getValue();
                    break;
                }
            }
        }
        if (v == null) return Math.max(0, fallback);
        try {
            return Math.max(0, Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return Math.max(0, fallback);
        }
    }

    private ObjectiveWeights normalize(double cost, double success, double latency, double risk) {
        double sum = cost + success + latency + risk;
        if (sum <= 0) return defaultWeights();
        return new ObjectiveWeights(cost / sum, success / sum, latency / sum, risk / sum);
    }

    // ==================== 条件匹配 ====================

    /** 条件匹配语义与 RoutingRule.matches 一致（currency/amount_gte/amount_lte，分）。 */
    private boolean matches(Map<String, String> conditions, RoutingContext ctx) {
        if (conditions == null || conditions.isEmpty()) return true;
        String currency = ctx == null ? null : ctx.currency();
        String condCurrency = conditions.get("currency");
        if (condCurrency != null && !condCurrency.equals(currency)) return false;
        long amount = toAmount(ctx);
        String amtGte = conditions.get("amount_gte");
        if (amtGte != null && amount < Long.parseLong(amtGte.trim())) return false;
        String amtLte = conditions.get("amount_lte");
        if (amtLte != null && amount > Long.parseLong(amtLte.trim())) return false;
        return true;
    }

    private long toAmount(RoutingContext ctx) {
        BigDecimal amount = ctx == null ? null : ctx.amount();
        return amount == null ? 0L : amount.longValue();
    }

    private int indexOf(List<PaymentConnector> candidates, String id) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).getId().equals(id)) return i;
        }
        return Integer.MAX_VALUE;
    }

    // ==================== CRUD ====================

    public List<RoutingStrategyConfig> listAll() {
        return repository.findAll();
    }

    public List<RoutingStrategyConfig> listEnabled() {
        return repository.findByEnabledTrueOrderByPriorityDesc();
    }

    public RoutingStrategyConfig get(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("策略配置不存在: id=" + id));
    }

    @Transactional
    public RoutingStrategyConfig create(RoutingStrategyConfig config, String operator) {
        validate(config);
        repository.findByName(config.getName()).ifPresent(existing -> {
            throw new IllegalArgumentException("策略配置名称已存在: " + config.getName());
        });
        config.setCreatedBy(operator);
        RoutingStrategyConfig saved = repository.save(config);
        evictCache();
        log.info("创建多目标路由策略配置: id={}, name={}, operator={}", saved.getId(), saved.getName(), operator);
        return saved;
    }

    @Transactional
    public RoutingStrategyConfig update(Long id, RoutingStrategyConfig update, String operator) {
        RoutingStrategyConfig existing = get(id);
        if (update.getName() != null && !update.getName().equals(existing.getName())) {
            repository.findByName(update.getName()).ifPresent(other -> {
                if (!other.getId().equals(id)) {
                    throw new IllegalArgumentException("策略配置名称已存在: " + update.getName());
                }
            });
            existing.setName(update.getName());
        }
        if (update.getObjectiveWeightsJson() != null) {
            existing.setObjectiveWeightsJson(update.getObjectiveWeightsJson());
        }
        if (update.getConditionsJson() != null) {
            existing.setConditionsJson(update.getConditionsJson());
        }
        // priority/enabled 为原始类型：更新 DTO 总是携带完整值（整对象覆盖语义）
        existing.setPriority(update.getPriority());
        existing.setEnabled(update.isEnabled());
        validate(existing);
        RoutingStrategyConfig saved = repository.save(existing);
        evictCache();
        log.info("更新多目标路由策略配置: id={}, operator={}", id, operator);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        repository.deleteById(id);
        evictCache();
        log.info("删除多目标路由策略配置: id={}", id);
    }

    private void validate(RoutingStrategyConfig config) {
        if (config.getName() == null || config.getName().isBlank()) {
            throw new IllegalArgumentException("策略配置名称不能为空");
        }
        if (config.getObjectiveWeightsJson() == null || config.getObjectiveWeightsJson().isBlank()) {
            throw new IllegalArgumentException("目标权重 JSON 不能为空");
        }
        if (parseWeights(config.getObjectiveWeightsJson()) == null) {
            throw new IllegalArgumentException("目标权重 JSON 非法（需包含 COST/SUCCESS_RATE/LATENCY/RISK 之一）");
        }
    }
}
