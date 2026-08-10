package org.nexus.gateway.orchestration.routing;

import java.util.List;
import java.util.Map;

/**
 * A routing rule defines conditions + strategy + ordered connector list.
 */
public class RoutingRule {
    private String id;
    private String name;
    private Map<String, String> conditions;
    private RoutingStrategy strategy;
    private List<String> connectors;
    private int priority;

    public RoutingRule() {}

    public RoutingRule(String id, String name, Map<String, String> conditions,
                       RoutingStrategy strategy, List<String> connectors, int priority) {
        this.id = id;
        this.name = name;
        this.conditions = conditions;
        this.strategy = strategy;
        this.connectors = connectors;
        this.priority = priority;
    }

    public boolean matches(String currency, long amount) {
        if (conditions == null || conditions.isEmpty()) return true;
        String condCurrency = conditions.get("currency");
        if (condCurrency != null && !condCurrency.equals(currency)) return false;
        String amtGte = conditions.get("amount_gte");
        if (amtGte != null && amount < Long.parseLong(amtGte)) return false;
        String amtLte = conditions.get("amount_lte");
        if (amtLte != null && amount > Long.parseLong(amtLte)) return false;
        return true;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Map<String, String> getConditions() { return conditions; }
    public void setConditions(Map<String, String> conditions) { this.conditions = conditions; }
    public RoutingStrategy getStrategy() { return strategy; }
    public void setStrategy(RoutingStrategy strategy) { this.strategy = strategy; }
    public List<String> getConnectors() { return connectors; }
    public void setConnectors(List<String> connectors) { this.connectors = connectors; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
}