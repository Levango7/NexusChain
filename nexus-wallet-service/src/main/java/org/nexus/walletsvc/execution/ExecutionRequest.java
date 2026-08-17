package org.nexus.walletsvc.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 三阶段执行模式的请求封装（P2-F3，wallet-service 本地副本）。
 *
 * <p>与 {@code org.nexus.gateway.execution.ExecutionRequest} 语义一致，
 * 因模块隔离在 wallet-service 中保留独立副本。承载一次链上副作用操作的「意图」描述。</p>
 */
public final class ExecutionRequest {

    public enum OperationType {
        REFUND,
        WITHDRAWAL,
        SETTLEMENT
    }

    private final OperationType operationType;
    private final BigDecimal amount;
    private final String targetAddress;
    private final String sourceAddress;
    private final String idempotencyKey;
    private final String asset;
    private final String businessRefId;
    private final Instant createdAt;

    public ExecutionRequest(OperationType operationType,
                            BigDecimal amount,
                            String targetAddress,
                            String sourceAddress,
                            String idempotencyKey,
                            String asset,
                            String businessRefId) {
        this(operationType, amount, targetAddress, sourceAddress,
                idempotencyKey, asset, businessRefId, Instant.now());
    }

    public ExecutionRequest(OperationType operationType,
                            BigDecimal amount,
                            String targetAddress,
                            String sourceAddress,
                            String idempotencyKey,
                            String asset,
                            String businessRefId,
                            Instant createdAt) {
        this.operationType = Objects.requireNonNull(operationType, "operationType");
        this.amount = Objects.requireNonNull(amount, "amount");
        this.targetAddress = Objects.requireNonNull(targetAddress, "targetAddress");
        this.sourceAddress = sourceAddress;
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.asset = asset != null ? asset : "NEX";
        this.businessRefId = businessRefId;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public OperationType getOperationType() { return operationType; }
    public BigDecimal getAmount() { return amount; }
    public String getTargetAddress() { return targetAddress; }
    public String getSourceAddress() { return sourceAddress; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getAsset() { return asset; }
    public String getBusinessRefId() { return businessRefId; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "ExecutionRequest{type=" + operationType
                + ", amount=" + amount
                + ", target='" + targetAddress + '\''
                + ", idempotencyKey='" + idempotencyKey + '\''
                + ", businessRefId='" + businessRefId + '\'' + '}';
    }
}