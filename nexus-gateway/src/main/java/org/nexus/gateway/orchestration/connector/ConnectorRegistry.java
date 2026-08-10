package org.nexus.gateway.orchestration.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of all available payment connectors.
 * Connectors self-register via Spring component scanning.
 */
@Component
public class ConnectorRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConnectorRegistry.class);
    private final Map<String, PaymentConnector> connectors = new ConcurrentHashMap<>();

    public ConnectorRegistry(List<PaymentConnector> connectorList) {
        for (PaymentConnector c : connectorList) {
            connectors.put(c.getId(), c);
            log.info("Registered payment connector: {} (type={}, active={})", c.getId(), c.getType(), c.isActive());
        }
        log.info("ConnectorRegistry initialized with {} connectors", connectors.size());
    }

    public Optional<PaymentConnector> get(String id) {
        return Optional.ofNullable(connectors.get(id));
    }

    public Collection<PaymentConnector> getAll() {
        return Collections.unmodifiableCollection(connectors.values());
    }

    public List<PaymentConnector> getActive() {
        return connectors.values().stream()
                .filter(PaymentConnector::isActive)
                .toList();
    }

    public List<PaymentConnector> getActiveForCurrency(String currency) {
        return connectors.values().stream()
                .filter(PaymentConnector::isActive)
                .filter(c -> c.supportedCurrencies().isEmpty() || c.supportedCurrencies().contains(currency))
                .toList();
    }

    public void register(PaymentConnector connector) {
        connectors.put(connector.getId(), connector);
        log.info("Dynamically registered connector: {}", connector.getId());
    }

    public void unregister(String id) {
        connectors.remove(id);
        log.info("Unregistered connector: {}", id);
    }
}