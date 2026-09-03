package org.nexus.gateway.orchestration.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Orchestrated Payment - the central entity tracking a payment through the orchestration engine.
 */
@Entity
@Table(name = "orchestrated_payments", indexes = {
    @Index(name = "idx_op_merchant", columnList = "merchantId"),
    @Index(name = "idx_op_status", columnList = "status"),
    @Index(name = "idx_op_connector", columnList = "connectorId")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_op_request_id", columnNames = "request_id")
})
public class OrchestratedPayment {

    @Id
    @Column(length = 64)
    private String id;

    // Idempotency key supplied by the caller. Enforced unique at DB level
    // (uk_op_request_id) so duplicate creates collapse to the same payment.
    // Nullable: not every payment carries an explicit request_id.
    @Column(name = "request_id", length = 128)
    private String requestId;

    @Column(nullable = false)
    private Long merchantId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 16)
    private String currency;

    @Column(length = 512)
    private String description;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private OrchPaymentStatus status;

    @Column(length = 64)
    private String connectorId;

    /** Connector 端到端耗时（毫秒），仅当实际测量后填充（可为 null）。 */
    @Column(name = "latency_ms")
    private Long latencyMs;

    /** Connector 成本（basis points），仅当实际测量后填充（可为 null）。 */
    @Column(name = "cost_bps")
    private Integer costBps;

    @Column(length = 128)
    private String connectorPaymentId;

    @Column(length = 128)
    private String transactionHash;

    @Column(length = 512)
    private String notifyUrl;

    @Column(length = 32)
    private String routingStrategy;

    @Column(length = 1024)
    private String metadata;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant confirmedAt;
    private Instant expiresAt;

    @Version
    private Long version;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (expiresAt == null) expiresAt = createdAt.plusSeconds(1800);
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public OrchPaymentStatus getStatus() { return status; }
    public void setStatus(OrchPaymentStatus status) { this.status = status; }
    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }
    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }
    public Integer getCostBps() { return costBps; }
    public void setCostBps(Integer costBps) { this.costBps = costBps; }
    public String getConnectorPaymentId() { return connectorPaymentId; }
    public void setConnectorPaymentId(String connectorPaymentId) { this.connectorPaymentId = connectorPaymentId; }
    public String getTransactionHash() { return transactionHash; }
    public void setTransactionHash(String transactionHash) { this.transactionHash = transactionHash; }
    public String getNotifyUrl() { return notifyUrl; }
    public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }
    public String getRoutingStrategy() { return routingStrategy; }
    public void setRoutingStrategy(String routingStrategy) { this.routingStrategy = routingStrategy; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
