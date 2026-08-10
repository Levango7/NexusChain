package org.nexus.gateway.orchestration.connector;

import java.time.Instant;

public class ConnectorHealth {
    private String connectorId;
    private boolean healthy;
    private String message;
    private Instant checkedAt;
    private long latencyMs;

    public static ConnectorHealth up(String connectorId, long latencyMs) {
        ConnectorHealth h = new ConnectorHealth();
        h.connectorId = connectorId;
        h.healthy = true;
        h.message = "OK";
        h.checkedAt = Instant.now();
        h.latencyMs = latencyMs;
        return h;
    }

    public static ConnectorHealth down(String connectorId, String reason) {
        ConnectorHealth h = new ConnectorHealth();
        h.connectorId = connectorId;
        h.healthy = false;
        h.message = reason;
        h.checkedAt = Instant.now();
        h.latencyMs = -1;
        return h;
    }

    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String id) { this.connectorId = id; }
    public boolean isHealthy() { return healthy; }
    public void setHealthy(boolean healthy) { this.healthy = healthy; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Instant checkedAt) { this.checkedAt = checkedAt; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
}