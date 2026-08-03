package org.nexus.gateway.orchestration.connectors;

import org.nexus.gateway.orchestration.connector.*;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock connector for sandbox/testing. Always succeeds with configurable delay.
 */
@Component
public class MockConnector implements PaymentConnector {

    private final Map<String, PaymentStatus> payments = new ConcurrentHashMap<>();

    @Override
    public String getId() { return "mock"; }

    @Override
    public String getType() { return "mock"; }

    @Override
    public String getDisplayName() { return "Mock Connector (Sandbox)"; }

    @Override
    public boolean isActive() { return true; }

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        String connectorId = "mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        payments.put(connectorId, PaymentStatus.SUCCEEDED);
        return ConnectorPaymentResult.ok(connectorId, PaymentStatus.SUCCEEDED, "mock_tx_" + connectorId);
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        return payments.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        if (payments.containsKey(connectorPaymentId)) {
            payments.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok("refund_" + connectorPaymentId);
        }
        return ConnectorRefundResult.fail("Payment not found");
    }

    @Override
    public ConnectorHealth healthCheck() {
        return ConnectorHealth.up(getId(), 1);
    }

    @Override
    public Set<String> supportedCurrencies() { return Set.of(); }

    @Override
    public int feeBasisPoints() { return 0; }
}