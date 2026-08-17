package org.nexus.gateway.execution;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 三阶段执行模式的请求封装（P2-F3）。
 *
 * <p>承载一次链上副作用操作的「意图」描述，由阶段1（落库 PENDING）持久化，
 * 阶段2（链上执行）读取必要字段发起链上交易，阶段3（更新 CONFIRMED/FAILED）
 * 根据链上结果回写状态。所有字段均为不可变快照，避免跨阶段共享可变状态。</p>
 *
 * <ul>
 *   <li>{@code operationType}：操作类型（REFUND / WITHDRAWAL / SETTLEMENT），
 *       供 {@link CompensationService} 选择补偿策略</li>
 *   <li>{@code amount}：操作金额（最小单位）</li>
 *   <li>{@code targetAddress}：链上目标地址（退款接收方 / 提现目标 / 结算对手）</li>
 *   <li>{@code sourceAddress}：链上源地址（平台热钱包 / 商户结算钱包）</li>
 *   <li>{@code idempotencyKey}：幂等键，跨阶段唯一标识本次操作意图，
 *       供链上查询与补偿去重使用</li>
 *   <li>{@code asset}：资产标识，默认 NEX</li>
 *   <li>{@code businessRefId}：业务引用 ID（订单 ID / 提现请求 ID 等），
 *       用于跨表关联与日志追踪</li>
 *   <li>{@code createdAt}：请求创建时间，用于 PENDING 超时判定</li>
 * </ul>
 */
public final class ExecutionRequest {

    /** 操作类型枚举 */
    public enum OperationType {
        /** 退款转账 */
        REFUND,
        /** 提现转账 */
        WITHDRAWAL,
        /** 结算转账 */
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
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ExecutionRequest)) return false;
        ExecutionRequest that = (ExecutionRequest) o;
        return operationType == that.operationType
                && Objects.equals(amount, that.amount)
                && Objects.equals(targetAddress, that.targetAddress)
                && Objects.equals(sourceAddress, that.sourceAddress)
                && Objects.equals(idempotencyKey, that.idempotencyKey)
                && Objects.equals(asset, that.asset)
                && Objects.equals(businessRefId, that.businessRefId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operationType, amount, targetAddress, sourceAddress,
                idempotencyKey, asset, businessRefId);
    }

    @Override
    public String toString() {
        return "ExecutionRequest{type=" + operationType
                + ", amount=" + amount
                + ", target='" + targetAddress + '\''
                + ", source='" + sourceAddress + '\''
                + ", idempotencyKey='" + idempotencyKey + '\''
                + ", asset='" + asset + '\''
                + ", businessRefId='" + businessRefId + '\''
                + ", createdAt=" + createdAt + '}';
    }
}