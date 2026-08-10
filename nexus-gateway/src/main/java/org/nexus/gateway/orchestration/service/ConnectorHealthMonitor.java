package org.nexus.gateway.orchestration.service;

import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodic health monitor for all registered connectors.
 * Runs every 30s, tracks consecutive failures, auto-disables after 3 failures.
 * Re-enables when health check passes again.
 */
@Component
public class ConnectorHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(ConnectorHealthMonitor.class);
    private static final int FAILURE_THRESHOLD = 3;

    private final ConnectorRegistry registry;
    private final Map<String, Integer> failureCounts = new ConcurrentHashMap<>();
    private final Map<String, ConnectorHealth> lastHealth = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastChecked = new ConcurrentHashMap<>();

    public ConnectorHealthMonitor(ConnectorRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 10000)
    public void checkAllConnectors() {
        for (PaymentConnector connector : registry.getAll()) {
            try {
                ConnectorHealth health = connector.healthCheck();
                lastHealth.put(connector.getId(), health);
                lastChecked.put(connector.getId(), Instant.now());

                if (health.isHealthy()) {
                    int prevFailures = failureCounts.getOrDefault(connector.getId(), 0);
                    failureCounts.put(connector.getId(), 0);
                    if (prevFailures >= FAILURE_THRESHOLD) {
                        log.info("Connector {} recovered after {} failures", connector.getId(), prevFailures);
                    }
                } else {
                    int failures = failureCounts.merge(connector.getId(), 1, Integer::sum);
                    log.warn("Connector {} unhealthy (failure #{}): {}", connector.getId(), failures, health.getMessage());
                    if (failures >= FAILURE_THRESHOLD) {
                        log.error("Connector {} disabled after {} consecutive failures", connector.getId(), failures);
                    }
                }
            } catch (Exception e) {
                int failures = failureCounts.merge(connector.getId(), 1, Integer::sum);
                lastHealth.put(connector.getId(), ConnectorHealth.down(connector.getId(), e.getMessage()));
                log.error("Connector {} health check threw exception (failure #{}): {}", connector.getId(), failures, e.getMessage());
            }
        }
    }

    public boolean isConnectorAvailable(String connectorId) {
        return failureCounts.getOrDefault(connectorId, 0) < FAILURE_THRESHOLD;
    }

    public ConnectorHealth getLastHealth(String connectorId) {
        return lastHealth.get(connectorId);
    }

    public Instant getLastChecked(String connectorId) {
        return lastChecked.get(connectorId);
    }

    public Map<String, Integer> getFailureCounts() {
        return Map.copyOf(failureCounts);
    }
}