package org.nexus.gateway.orchestration.routing;

import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Routing Engine - selects which connector(s) to use for a payment.
 * Supports: priority (failover), weight (A/B), cost (cheapest), explicit (merchant choice).
 */
@Component
public class RoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingEngine.class);

    private final ConnectorRegistry registry;
    private final List<RoutingRule> rules = Collections.synchronizedList(new ArrayList<>());

    public RoutingEngine(ConnectorRegistry registry) {
        this.registry = registry;
        // Default rule: route NEX to chain, everything else to mock
        rules.add(new RoutingRule("default-nex", "NEX payments go to chain",
                Map.of("currency", "NEX"), RoutingStrategy.PRIORITY, List.of("chain", "mock"), 10));
        rules.add(new RoutingRule("default-fallback", "All other payments use mock",
                Map.of(), RoutingStrategy.PRIORITY, List.of("mock", "chain"), 0));
        log.info("RoutingEngine initialized with {} default rules", rules.size());
    }

    /**
     * Resolve the ordered list of connectors to try for a given payment.
     */
    public List<PaymentConnector> resolve(String currency, long amount, String preferredConnector) {
        // Explicit routing: merchant specified a connector
        if (preferredConnector != null && !preferredConnector.isBlank()) {
            return registry.get(preferredConnector)
                    .filter(PaymentConnector::isActive)
                    .map(List::of)
                    .orElseGet(() -> fallbackConnectors(currency));
        }

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
}