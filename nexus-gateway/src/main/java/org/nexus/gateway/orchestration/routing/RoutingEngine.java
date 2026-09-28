package org.nexus.gateway.orchestration.routing;

import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.config.RoutingWave16Properties;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.model.RoutingRuleEntity;
import org.nexus.gateway.orchestration.repository.RoutingRuleEntityRepository;
import org.nexus.gateway.orchestration.routing.ai.AbTestRouter;
import org.nexus.gateway.orchestration.routing.audit.RoutingAuditService;
import org.nexus.gateway.orchestration.routing.experiment.RoutingExperimentService;
import org.nexus.gateway.orchestration.routing.fallback.FallbackRouteService;
import org.nexus.gateway.orchestration.routing.profile.MerchantRoutingProfileService;
import org.nexus.gateway.orchestration.routing.strategy.MultiObjectiveRoutingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Routing Engine - selects which connector(s) to use for a payment.
 * Supports: priority (failover), weight (A/B), cost (cheapest), explicit (merchant choice).
 *
 * <p><b>Dual-chain routing</b>: when {@code nexus.routing.dual-chain.enabled=true},
 * two additional rules are registered with priority above the default NEX/fallback
 * rules but below any merchant-supplied rule (priority 50):
 * <ul>
 *   <li>small-amount (amount &lt; threshold) → consortium first, chain as failover</li>
 *   <li>large-amount (amount &ge; threshold) → chain first, consortium as failover</li>
 * </ul>
 * This keeps the consortium sidechain (PoA, low latency) for small/ high-frequency
 * payments and the public core mainnet (PoW, final settlement) for large payments.
 * Failover stays within the same preferred group. Existing strategies
 * (priority/weight/cost/explicit) and the default NEX→chain rule are preserved
 * when dual-chain is disabled or no dual-chain rule matches.</p>
 *
 * <p><b>P4-T4 AI 路由集成</b>：当 {@code nexus.routing.ai.enabled=true} 且
 * {@link AbTestRouter} 已注入时，先走规则路由得到候选列表，再通过 A/B 测试
 * 框架决定是否用 AI 模型重排序。AI 路由推荐失败（模型异常或样本不足）时，
 * A/B 测试框架自动降级到规则路由结果。{@code preferredConnector} 非空
 * （explicit 路由）时跳过 AI 路由，尊重商户显式选择。</p>
 */
@Component
public class RoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingEngine.class);

    /** Priority reserved for dual-chain rules (above default rules, below merchant rules). */
    private static final int DUAL_CHAIN_PRIORITY = 50;

    private final ConnectorRegistry registry;
    private final GatewayConfig gatewayConfig;
    private final List<RoutingRule> rules = Collections.synchronizedList(new ArrayList<>());
    /** A/B 测试路由器，nullable（AI 路由禁用或测试环境时为 null）。 */
    private final AbTestRouter abTestRouter;
    /** 路由规则持久化仓库，nullable（纯内存模式/测试环境时为 null）。 */
    private final RoutingRuleEntityRepository ruleRepository;

    // === Wave 16 可选协作方（全部 nullable：测试/未启用时缺省，不阻断既有路径） ===
    /** 多目标路由策略服务（模块一）。 */
    private final MultiObjectiveRoutingService multiObjectiveRoutingService;
    /** 路由决策审计服务（模块五）。 */
    private final RoutingAuditService routingAuditService;
    /** 路由 A/B 实验服务（模块四）。 */
    private final RoutingExperimentService routingExperimentService;
    /** 路由监控服务（模块七）。 */
    private final org.nexus.gateway.orchestration.routing.monitor.RoutingMonitorService routingMonitorService;
    /** 降级路由服务（模块三）。 */
    private final FallbackRouteService fallbackRouteService;
    /** 商户路由画像服务（模块六）。 */
    private final MerchantRoutingProfileService profileService;
    /** Wave 16 配置（nullable，纯测试场景）。 */
    private final RoutingWave16Properties wave16Properties;

    public RoutingEngine(ConnectorRegistry registry, GatewayConfig gatewayConfig) {
        this(registry, gatewayConfig, null, null, null, null, null, null, null, null, null);
    }

    public RoutingEngine(ConnectorRegistry registry, GatewayConfig gatewayConfig,
                         AbTestRouter abTestRouter) {
        this(registry, gatewayConfig, abTestRouter, null, null, null, null, null, null, null, null);
    }

    /**
     * 全量构造器（含路由规则 DB 持久化；Wave 16 协作方全部缺省 = Wave 15 行为）。
     *
     * <p>规则 DB 同步语义见 {@link #RoutingEngine(ConnectorRegistry, GatewayConfig,
     * AbTestRouter, RoutingRuleEntityRepository, MultiObjectiveRoutingService,
     * RoutingAuditService, RoutingExperimentService, org.nexus.gateway.orchestration.routing.monitor.RoutingMonitorService,
     * FallbackRouteService, MerchantRoutingProfileService, RoutingWave16Properties)}。</p>
     */
    public RoutingEngine(ConnectorRegistry registry, GatewayConfig gatewayConfig,
                         AbTestRouter abTestRouter, RoutingRuleEntityRepository ruleRepository) {
        this(registry, gatewayConfig, abTestRouter, ruleRepository,
                null, null, null, null, null, null, null);
    }

    /**
     * Spring 装配构造器（Wave 16 完整形态，11 参）。
     *
     * <p>Wave 16 各协作方均为 optional 注入——对应模块的 Bean 缺失
     * （测试环境/后续裁剪）时传 null，引擎自动退化为 Wave 15 行为。</p>
     *
     * <p>规则 DB 同步（{@code ruleRepository} 非 null 时）：
     * DB 为空 → 幂等种子默认规则；DB 有数据 → 以 DB 恢复内存规则；
     * DB 异常 → 降级纯内存默认规则，不阻断初始化。</p>
     */
    @Autowired
    public RoutingEngine(ConnectorRegistry registry, GatewayConfig gatewayConfig,
                         @Autowired(required = false) AbTestRouter abTestRouter,
                         @Autowired(required = false) RoutingRuleEntityRepository ruleRepository,
                         @Autowired(required = false) MultiObjectiveRoutingService multiObjectiveRoutingService,
                         @Autowired(required = false) RoutingAuditService routingAuditService,
                         @Autowired(required = false) RoutingExperimentService routingExperimentService,
                         @Autowired(required = false) org.nexus.gateway.orchestration.routing.monitor.RoutingMonitorService routingMonitorService,
                         @Autowired(required = false) FallbackRouteService fallbackRouteService,
                         @Autowired(required = false) MerchantRoutingProfileService profileService,
                         @Autowired(required = false) RoutingWave16Properties wave16Properties) {
        this.registry = registry;
        this.gatewayConfig = gatewayConfig;
        this.abTestRouter = abTestRouter;
        this.ruleRepository = ruleRepository;
        this.multiObjectiveRoutingService = multiObjectiveRoutingService;
        this.routingAuditService = routingAuditService;
        this.routingExperimentService = routingExperimentService;
        this.routingMonitorService = routingMonitorService;
        this.fallbackRouteService = fallbackRouteService;
        this.profileService = profileService;
        this.wave16Properties = wave16Properties;
        initRules(abTestRouter, ruleRepository);
    }

    /**
     * 规则初始化：dual-chain 规则注册 + 默认规则 + DB 同步。
     *
     * <p>Dual-chain rules are registered first so merchant-added rules at
     * priority &gt; 50 still win; the default NEX/fallback rules below at
     * priority 10/0 only apply when no dual-chain rule matches.</p>
     */
    private void initRules(AbTestRouter abTestRouter, RoutingRuleEntityRepository ruleRepository) {
        registerDualChainRulesIfEnabled();

        // Default rule: route NEX to chain, everything else to mock
        rules.add(new RoutingRule("default-nex", "NEX payments go to chain",
                Map.of("currency", "NEX"), RoutingStrategy.PRIORITY, List.of("chain", "mock"), 10));
        rules.add(new RoutingRule("default-fallback", "All other payments use mock",
                Map.of(), RoutingStrategy.PRIORITY, List.of("mock", "chain"), 0));

        // 持久化同步（异常降级为纯内存，不阻断初始化）
        if (ruleRepository != null) {
            try {
                if (ruleRepository.count() == 0) {
                    ruleRepository.saveAll(rules.stream().map(this::toEntity).collect(Collectors.toList()));
                    log.info("RoutingEngine: seeded {} default rules to DB", rules.size());
                } else {
                    List<RoutingRule> restored = ruleRepository.findAll().stream()
                            .map(this::fromEntity)
                            .collect(Collectors.toList());
                    if (!restored.isEmpty()) {
                        rules.clear();
                        rules.addAll(restored);
                        log.info("RoutingEngine: restored {} rules from DB", restored.size());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("RoutingEngine: DB sync failed, degraded to in-memory defaults: {}", e.getMessage());
            }
        }

        log.info("RoutingEngine initialized with {} rules, aiRouting={}, dbPersist={}",
                rules.size(), abTestRouter != null, ruleRepository != null);
    }

    /**
     * Register dual-chain routing rules when the policy is enabled.
     *
     * <p>Small-amount rule: {@code amount < threshold} → [consortium, chain].
     * Large-amount rule: {@code amount >= threshold} → [chain, consortium].
     * Both use {@link RoutingStrategy#PRIORITY} so the connector list is tried
     * in order with in-group failover.</p>
     */
    private void registerDualChainRulesIfEnabled() {
        GatewayConfig.RoutingConfig routing = gatewayConfig.getRouting();
        if (routing == null) return;
        GatewayConfig.DualChainConfig dualChain = routing.getDualChain();
        if (dualChain == null || !dualChain.isEnabled()) return;

        long threshold = dualChain.getSmallAmountThreshold();
        List<String> smallConnectors = dualChain.getSmall();
        List<String> largeConnectors = dualChain.getLarge();

        // amount_lte = threshold - 1  <=>  amount < threshold
        rules.add(new RoutingRule("dual-chain-small",
                "Small-amount payments go to consortium first (low latency)",
                Map.of("amount_lte", String.valueOf(Math.max(0, threshold - 1))),
                RoutingStrategy.PRIORITY,
                smallConnectors,
                DUAL_CHAIN_PRIORITY));
        // amount_gte = threshold  <=>  amount >= threshold
        rules.add(new RoutingRule("dual-chain-large",
                "Large-amount payments go to core first (public settlement)",
                Map.of("amount_gte", String.valueOf(threshold)),
                RoutingStrategy.PRIORITY,
                largeConnectors,
                DUAL_CHAIN_PRIORITY));
        log.info("Dual-chain routing enabled: threshold={}, small={}, large={}",
                threshold, smallConnectors, largeConnectors);
    }

    /**
     * Resolve the ordered list of connectors to try for a given payment.
     *
     * <p>P4-T4：当 AI 路由启用且非 explicit 路由时，先走规则路由得到候选列表，
     * 再通过 {@link AbTestRouter} 决定使用 AI 还是规则路由结果。AI 路由推荐
     * 失败时 A/B 测试框架自动降级到规则路由结果。</p>
     */
    public List<PaymentConnector> resolve(String currency, long amount, String preferredConnector) {
        return resolveDetailed(RoutingContext.ofPayment(null, null, BigDecimal.valueOf(amount),
                        currency, preferredConnector))
                .connectors();
    }

    /**
     * Wave 16 完整决策流：explicit → 规则路由（含 MULTI_OBJECTIVE）→ AI 路由（P4-T4）
     * → 降级链追加（模块三）→ A/B 实验重排（模块四）→ 决策审计（模块五）。
     *
     * <p>{@code ctx.paymentId() == null}（非支付链路调用）时不参与实验分流、不落审计。</p>
     *
     * @param ctx 路由上下文（金额单位与规则条件一致：分）
     * @return 决策结果（含审计所需的全部元数据）
     */
    public RoutingDecision resolveDetailed(RoutingContext ctx) {
        String currency = ctx.currency();
        long amount = ctx.amount() == null ? 0L : ctx.amount().longValue();
        String preferredConnector = ctx.preferredConnector();

        // Explicit routing: merchant specified a connector — 跳过 AI/实验路由，尊重显式选择
        if (preferredConnector != null && !preferredConnector.isBlank()) {
            List<PaymentConnector> explicit = registry.get(preferredConnector)
                    .filter(PaymentConnector::isActive)
                    .map(List::of)
                    .orElseGet(() -> fallbackConnectors(currency));
            return finish(ctx, "explicit", "EXPLICIT", List.of(preferredConnector),
                    new LinkedHashMap<>(), explicit, null, null);
        }

        // 模块六：商户路由画像解析（失败降级为无画像）
        RoutingContext effectiveCtx = withResolvedProfile(ctx);

        // 规则路由得到候选列表
        RuleResolution resolution = resolveRule(effectiveCtx, amount);
        List<PaymentConnector> ruleResult = resolution.connectors();
        List<String> candidateIds = ids(ruleResult);
        LinkedHashMap<String, Double> scores = resolution.scores();
        String strategy = resolution.strategy();

        // P4-T4：AI 路由集成（A/B 测试分流）
        if (abTestRouter != null && ruleResult.size() > 1) {
            AbTestRouter.Decision decision = abTestRouter.decide(
                    ruleResult, ruleResult, amount, currency);
            if (log.isDebugEnabled()) {
                log.debug("Routing decision: method={}, degraded={}, connectors={}",
                        decision.method(), decision.degraded(),
                        decision.connectors().stream().map(PaymentConnector::getId).toList());
            }
            ruleResult = decision.connectors();
            if ("ai".equals(decision.method())) {
                strategy = "AI";
            }
        }

        // 模块三：单候选时按商户/全局降级配置追加备选链（多候选规则已自带顺序，尊重之）
        if (fallbackRouteService != null && featureEnabled("fallback") && ruleResult.size() == 1) {
            ruleResult = applyFallbackChain(effectiveCtx, ruleResult);
        }

        // 模块四：A/B 实验分流重排（仅支付链路；对照组沿用规则结果但记录归属）
        String experimentId = null;
        String abGroup = null;
        if (routingExperimentService != null && ctx.paymentId() != null) {
            try {
                ExperimentEffects effects = runExperimentAssignment(effectiveCtx, ruleResult);
                if (effects != null) {
                    experimentId = effects.experimentId();
                    abGroup = effects.groupId();
                    ruleResult = effects.connectors();
                }
            } catch (RuntimeException e) {
                log.warn("Experiment assignment failed, keeping rule result: {}", e.getMessage());
            }
        }

        return finish(effectiveCtx, resolution.ruleId(), strategy, candidateIds, scores,
                ruleResult, experimentId, abGroup);
    }

    /**
     * 实验分流：分配到实验组时按组配置重排候选（组外候选保持原相对顺序追加），
     * 对照组/空组配置沿用规则结果。无活跃实验返回 null。
     */
    private ExperimentEffects runExperimentAssignment(RoutingContext ctx, List<PaymentConnector> candidates) {
        Optional<RoutingExperimentService.Assignment> assignment =
                routingExperimentService.activeAssignment(ctx.paymentId());
        if (assignment.isEmpty()) return null;
        RoutingExperimentService.Assignment a = assignment.get();
        if (a.control() || a.connectorIds() == null || a.connectorIds().isEmpty()) {
            return new ExperimentEffects(candidates, a.experimentId(), a.groupId());
        }
        return new ExperimentEffects(reorder(candidates, a.connectorIds()), a.experimentId(), a.groupId());
    }

    /** 实验对候选列表的影响（重排后的列表 + 实验归属）。 */
    private record ExperimentEffects(List<PaymentConnector> connectors, String experimentId, String groupId) {}

    /** 收尾：审计落库 + 监控计数 + 打包决策结果。 */
    private RoutingDecision finish(RoutingContext ctx, String ruleId, String strategy,
                                   List<String> candidates, LinkedHashMap<String, Double> scores,
                                   List<PaymentConnector> finalResult, String experimentId, String abGroup) {
        List<String> decisionIds = ids(finalResult);
        String decisionId = auditDecision(ctx, ruleId, strategy, candidates, scores,
                decisionIds, experimentId, abGroup);
        if (routingMonitorService != null) {
            try {
                routingMonitorService.incrementDecisions(strategy);
            } catch (RuntimeException e) {
                log.debug("Monitor increment failed: {}", e.getMessage());
            }
        }
        return new RoutingDecision(finalResult, ruleId, strategy, candidates, scores,
                experimentId, abGroup, decisionId);
    }

    /**
     * 纯规则路由（不含 AI）：匹配规则 + 策略解析 + fallback。
     */
    private RuleResolution resolveRule(RoutingContext ctx, long amount) {
        // Find matching rule (highest priority first)
        RoutingRule matched = rules.stream()
                .filter(r -> r.matches(ctx.currency(), amount))
                .max(Comparator.comparingInt(RoutingRule::getPriority))
                .orElse(null);

        if (matched == null) {
            return new RuleResolution(fallbackConnectors(ctx.currency()), null, "FALLBACK",
                    new LinkedHashMap<>());
        }

        return switch (matched.getStrategy()) {
            case PRIORITY, EXPLICIT -> new RuleResolution(resolvePriority(matched, ctx.currency()),
                    matched.getId(), matched.getStrategy().name(), new LinkedHashMap<>());
            case WEIGHT -> new RuleResolution(resolveWeight(matched, ctx.currency()),
                    matched.getId(), matched.getStrategy().name(), new LinkedHashMap<>());
            case COST -> new RuleResolution(resolveCost(matched, ctx.currency(), amount),
                    matched.getId(), matched.getStrategy().name(), new LinkedHashMap<>());
            case MULTI_OBJECTIVE -> {
                MultiObjectiveResult result = resolveMultiObjective(matched, ctx, ctx.currency());
                yield new RuleResolution(result.connectors(), matched.getId(),
                        matched.getStrategy().name(), result.scores());
            }
        };
    }

    /** 规则解析结果。 */
    private record RuleResolution(List<PaymentConnector> connectors, String ruleId, String strategy,
                                  LinkedHashMap<String, Double> scores) {}

    /** 多目标解析结果。 */
    private record MultiObjectiveResult(List<PaymentConnector> connectors,
                                        LinkedHashMap<String, Double> scores) {}

    /**
     * Wave 16 模块一：MULTI_OBJECTIVE 策略 — 多目标评分降序。
     * 服务未注入（测试/裁剪）时退化为 PRIORITY 顺序（保持等价性）。
     */
    private MultiObjectiveResult resolveMultiObjective(RoutingRule rule, RoutingContext ctx, String currency) {
        List<PaymentConnector> candidates = new ArrayList<>();
        for (String id : rule.getConnectors()) {
            registry.get(id).filter(PaymentConnector::isActive).ifPresent(candidates::add);
        }
        if (candidates.isEmpty()) {
            return new MultiObjectiveResult(fallbackConnectors(currency), new LinkedHashMap<>());
        }
        if (multiObjectiveRoutingService == null) {
            return new MultiObjectiveResult(candidates, new LinkedHashMap<>());
        }
        try {
            MultiObjectiveRoutingService.MultiObjectiveDecision decision =
                    multiObjectiveRoutingService.decide(ctx, candidates);
            List<PaymentConnector> ordered = new ArrayList<>();
            for (String id : decision.orderedIds()) {
                candidates.stream().filter(c -> c.getId().equals(id)).findFirst().ifPresent(ordered::add);
            }
            if (ordered.size() != candidates.size()) {
                // 评分结果缺项（异常兜底）：缺失者按原顺序追加
                for (PaymentConnector c : candidates) {
                    if (!ordered.contains(c)) ordered.add(c);
                }
            }
            return new MultiObjectiveResult(ordered, decision.scores());
        } catch (RuntimeException e) {
            log.warn("MULTI_OBJECTIVE scoring failed, falling back to rule order: {}", e.getMessage());
            return new MultiObjectiveResult(candidates, new LinkedHashMap<>());
        }
    }

    /** 模块六：解析商户路由画像（profileService 缺失 / 商户为空 / 禁用时原样返回）。 */
    private RoutingContext withResolvedProfile(RoutingContext ctx) {
        if (profileService == null || ctx.merchantId() == null || !featureEnabled("profile")) {
            return ctx;
        }
        try {
            Object profile = profileService.resolve(ctx.merchantId(), null, ctx.amount());
            return profile == null ? ctx : ctx.withProfile(profile);
        } catch (RuntimeException e) {
            log.warn("Profile resolution failed, continuing without profile: {}", e.getMessage());
            return ctx;
        }
    }

    /** 模块三：单候选时追加降级链（registry 过滤激活 connector，去重，失败降级为原列表）。 */
    private List<PaymentConnector> applyFallbackChain(RoutingContext ctx, List<PaymentConnector> single) {
        try {
            String primary = single.get(0).getId();
            List<String> chain = fallbackRouteService.resolveFallbackChain(
                    ctx.merchantId(), primary, ctx.amount(), ctx.currency());
            if (chain.isEmpty()) return single;
            List<PaymentConnector> result = new ArrayList<>(single);
            for (String id : chain) {
                boolean present = result.stream().anyMatch(c -> c.getId().equals(id));
                if (present) continue;
                registry.get(id).filter(PaymentConnector::isActive).ifPresent(result::add);
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("Fallback chain resolution failed, keeping single candidate: {}", e.getMessage());
            return single;
        }
    }

    /** 按组配置顺序重排候选：组内 id 优先（∩ 候选），组外保持原相对顺序追加。 */
    private List<PaymentConnector> reorder(List<PaymentConnector> candidates, List<String> groupOrder) {
        List<PaymentConnector> result = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (String id : groupOrder) {
            candidates.stream().filter(c -> c.getId().equals(id)).findFirst().ifPresent(c -> {
                result.add(c);
                placed.add(c.getId());
            });
        }
        for (PaymentConnector c : candidates) {
            if (!placed.contains(c.getId())) result.add(c);
        }
        return result;
    }

    /** 模块五：决策审计（非支付链路 / 服务缺失 / 模块禁用时跳过）。 */
    private String auditDecision(RoutingContext ctx, String ruleId, String strategy,
                                 List<String> candidates, LinkedHashMap<String, Double> scores,
                                 List<String> decision, String experimentId, String abGroup) {
        if (routingAuditService == null || ctx.paymentId() == null) return null;
        if (wave16Properties != null && !wave16Properties.getAudit().isEnabled()) return null;
        try {
            return routingAuditService.recordDecision(ctx.paymentId(), ctx.merchantId(), ctx.amount(),
                    ctx.currency(), ruleId, strategy, candidates,
                    scores.isEmpty() ? null : scores, decision, experimentId, abGroup);
        } catch (RuntimeException e) {
            log.warn("Audit decision failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean featureEnabled(String module) {
        if (wave16Properties == null) return false;
        return switch (module) {
            case "fallback" -> wave16Properties.getFallback().isEnabled();
            case "profile" -> wave16Properties.getProfile().isEnabled();
            default -> false;
        };
    }

    private List<String> ids(List<PaymentConnector> connectors) {
        return connectors.stream().map(PaymentConnector::getId).collect(Collectors.toList());
    }

    private List<PaymentConnector> resolvePriority(RoutingRule rule, String currency) {
        List<PaymentConnector> result = new ArrayList<>();
        for (String id : rule.getConnectors()) {
            registry.get(id).filter(PaymentConnector::isActive).ifPresent(result::add);
        }
        if (result.isEmpty()) result.addAll(fallbackConnectors(currency));
        return result;
    }

    private List<PaymentConnector> resolveWeight(RoutingRule rule, String currency) {
        List<PaymentConnector> candidates = new ArrayList<>();
        for (String id : rule.getConnectors()) {
            registry.get(id).filter(PaymentConnector::isActive).ifPresent(candidates::add);
        }
        if (candidates.isEmpty()) return fallbackConnectors(currency);
        // Shuffle by weight (simple: random pick first, rest as failover)
        Collections.shuffle(candidates, ThreadLocalRandom.current());
        return candidates;
    }

    private List<PaymentConnector> resolveCost(RoutingRule rule, String currency, long amount) {
        List<PaymentConnector> candidates = new ArrayList<>();
        for (String id : rule.getConnectors()) {
            registry.get(id).filter(PaymentConnector::isActive).ifPresent(candidates::add);
        }
        if (candidates.isEmpty()) return fallbackConnectors(currency);
        candidates.sort(Comparator.comparingInt(PaymentConnector::feeBasisPoints));
        return candidates;
    }

    private List<PaymentConnector> fallbackConnectors(String currency) {
        List<PaymentConnector> active = registry.getActiveForCurrency(currency);
        if (active.isEmpty()) active = registry.getActive();
        return active;
    }

    // === Rule management ===

    public List<RoutingRule> getRules() { return Collections.unmodifiableList(rules); }

    public void addRule(RoutingRule rule) {
        rules.removeIf(r -> r.getId().equals(rule.getId()));
        rules.add(rule);
        persistSave(rule);
        log.info("Routing rule added/updated: {} (priority={})", rule.getId(), rule.getPriority());
    }

    /**
     * 更新路由规则（write-through 持久化，全局核验补全）。
     *
     * @param id   规则 id（强制覆盖 rule 的 id，路径语义）
     * @param rule 新规则内容
     * @return 更新后的规则（即入参 rule，id 已归一）
     */
    public RoutingRule updateRule(String id, RoutingRule rule) {
        rule.setId(id);
        rules.removeIf(r -> r.getId().equals(id));
        rules.add(rule);
        persistSave(rule);
        log.info("Routing rule updated: {} (priority={})", id, rule.getPriority());
        return rule;
    }

    public void removeRule(String id) {
        rules.removeIf(r -> r.getId().equals(id));
        persistDelete(id);
        log.info("Routing rule removed: {}", id);
    }

    /** write-through save（DB 异常不阻断内存操作） */
    private void persistSave(RoutingRule rule) {
        if (ruleRepository == null) return;
        try {
            ruleRepository.save(toEntity(rule));
        } catch (RuntimeException e) {
            log.warn("RoutingEngine: DB save failed for rule {} (in-memory kept): {}",
                    rule.getId(), e.getMessage());
        }
    }

    /** write-through delete（DB 异常不阻断内存操作） */
    private void persistDelete(String id) {
        if (ruleRepository == null) return;
        try {
            ruleRepository.deleteById(id);
        } catch (RuntimeException e) {
            log.warn("RoutingEngine: DB delete failed for rule {} (in-memory kept): {}",
                    id, e.getMessage());
        }
    }

    /** 领域规则 → 持久化实体 */
    private RoutingRuleEntity toEntity(RoutingRule rule) {
        RoutingRuleEntity e = new RoutingRuleEntity();
        e.setId(rule.getId());
        e.setName(rule.getName());
        e.setConditionsJson(RoutingRuleEntity.toConditionsJson(rule.getConditions()));
        e.setStrategy(rule.getStrategy().name());
        e.setConnectorsCsv(String.join(",", rule.getConnectors()));
        e.setPriority(rule.getPriority());
        return e;
    }

    /** 持久化实体 → 领域规则 */
    private RoutingRule fromEntity(RoutingRuleEntity e) {
        return new RoutingRule(
                e.getId(),
                e.getName(),
                RoutingRuleEntity.fromConditionsJson(e.getConditionsJson()),
                RoutingStrategy.valueOf(e.getStrategy()),
                List.of(e.getConnectorsCsv() == null || e.getConnectorsCsv().isBlank()
                        ? new String[0] : e.getConnectorsCsv().split(",")),
                e.getPriority());
    }

    /**
     * 获取 A/B 测试路由器（P4-T4）。AI 路由禁用时返回 null。
     * 供 OrchestrationService 在支付完成后回填 outcome。
     */
    public AbTestRouter getAbTestRouter() {
        return abTestRouter;
    }

    /**
     * Wave 16：按审计 decisionId 回填支付结果（模块五 outcome 回填）。
     * 审计服务缺失 / decisionId 为 null（未落审计）时 no-op。
     */
    public void recordRouteOutcome(String decisionId, boolean success) {
        if (routingAuditService == null || decisionId == null) return;
        routingAuditService.recordOutcome(decisionId, success);
    }
}
