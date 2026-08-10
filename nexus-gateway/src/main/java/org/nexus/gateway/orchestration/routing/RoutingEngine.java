package org.nexus.gateway.orchestration.routing;

import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.routing.ai.AbTestRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

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

    public RoutingEngine(ConnectorRegistry registry, GatewayConfig gatewayConfig) {
        this(registry, gatewayConfig, null);
    }

    @Autowired
    public RoutingEngine(ConnectorRegistry registry, GatewayConfig gatewayConfig,
                         @Autowired(required = false) AbTestRouter abTestRouter) {
        this.registry = registry;
        this.gatewayConfig = gatewayConfig;
        this.abTestRouter = abTestRouter;

        // Dual-chain routing rules (registered first so merchant-added rules at
        // priority > 50 still win; the default NEX/fallback rules below at
        // priority 10/0 only apply when no dual-chain rule matches).
        registerDualChainRulesIfEnabled();

        // Default rule: route NEX to chain, everything else to mock
        rules.add(new RoutingRule("default-nex", "NEX payments go to chain",
                Map.of("currency", "NEX"), RoutingStrategy.PRIORITY, List.of("chain", "mock"), 10));
        rules.add(new RoutingRule("default-fallback", "All other payments use mock",
                Map.of(), RoutingStrategy.PRIORITY, List.of("mock", "chain"), 0));
        log.info("RoutingEngine initialized with {} rules, aiRouting={}",
                rules.size(), abTestRouter != null);
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
        // Explicit routing: merchant specified a connector — 跳过 AI 路由，尊重显式选择
        if (preferredConnector != null && !preferredConnector.isBlank()) {
            return registry.get(preferredConnector)
                    .filter(PaymentConnector::isActive)
                    .map(List::of)
                    .orElseGet(() -> fallbackConnectors(currency));
        }

        // 规则路由得到候选列表
        List<PaymentConnector> ruleResult = resolveByRule(currency, amount);

        // P4-T4：AI 路由集成（A/B 测试分流）
        if (abTestRouter != null && ruleResult.size() > 1) {
            AbTestRouter.Decision decision = abTestRouter.decide(
                    ruleResult, ruleResult, amount, currency);
            if (log.isDebugEnabled()) {
                log.debug("Routing decision: method={}, degraded={}, connectors={}",
                        decision.method(), decision.degraded(),
                        decision.connectors().stream().map(PaymentConnector::getId).toList());
            }
            return decision.connectors();
        }

        return ruleResult;
    }

    /**
     * 纯规则路由（不含 AI）：匹配规则 + 策略解析 + fallback。
     */
    private List<PaymentConnector> resolveByRule(String currency, long amount) {
        // Find matching rule (highest priority first)
        RoutingRule matched = rules.stream()
                .filter(r -> r.matches(currency, amount))
                .max(Comparator.comparingInt(RoutingRule::getPriority))
                .orElse(null);

        if (matched == null) {
            return fallbackConnectors(currency);
        }

        return switch (matched.getStrategy()) {
            case PRIORITY -> resolvePriority(matched, currency);
            case WEIGHT -> resolveWeight(matched, currency);
            case COST -> resolveCost(matched, currency, amount);
            case EXPLICIT -> resolvePriority(matched, currency);
        };
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
        log.info("Routing rule added/updated: {} (priority={})", rule.getId(), rule.getPriority());
    }

    public void removeRule(String id) {
        rules.removeIf(r -> r.getId().equals(id));
        log.info("Routing rule removed: {}", id);
    }

    /**
     * 获取 A/B 测试路由器（P4-T4）。AI 路由禁用时返回 null。
     * 供 OrchestrationService 在支付完成后回填 outcome。
     */
    public AbTestRouter getAbTestRouter() {
        return abTestRouter;
    }
}
