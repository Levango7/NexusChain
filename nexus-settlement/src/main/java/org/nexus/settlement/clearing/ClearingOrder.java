package org.nexus.settlement.clearing;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 清算订单实体（JPA 持久化，账务核心）。
 * <p>
 * 描述单笔待结算的支付订单及其结算周期、状态等元数据。
 * 与支付层的事件模型（PaymentCompletedEvent）解耦，
 * 由 gateway 的 SettlementEventCollector 映射并按 PENDING 落库，
 * settle 成功后同键回写 SETTLED + settlementTxHash。
 * </p>
 *
 * <p>持久化设计：以 {@code orderId} 为业务主键（drain 删 PENDING → settle 同键 upsert）。
 * 保留 Jackson {@code @JsonProperty} 序列化与隐式无参构造，序列化测试零破坏。</p>
 */
@Entity
@Table(name = "clearing_order")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClearingOrder {

    /** 订单 ID（业务主键） */
    @Id
    @Column(name = "order_id", length = 64)
    @JsonProperty("orderId")
    private String orderId;

    /** 商户 ID */
    @Column(name = "merchant_id", length = 64, nullable = false)
    @JsonProperty("merchantId")
    private String merchantId;

    /** 金额 */
    @Column(name = "amount", precision = 36, scale = 8, nullable = false)
    @JsonProperty("amount")
    private BigDecimal amount;

    /** 币种 */
    @Column(name = "currency", length = 8, nullable = false)
    @JsonProperty("currency")
    private String currency;

    /** 结算周期 */
    @Column(name = "settlement_cycle", length = 16)
    @JsonProperty("settlementCycle")
    private String settlementCycle;

    /** 订单状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    @JsonProperty("status")
    private OrderStatus status;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMP(6)")
    @JsonProperty("createdAt")
    private Instant createdAt;

    /** 关联支付单 ID（来自 PaymentCompletedEvent） */
    @Column(name = "payment_id")
    @JsonProperty("paymentId")
    private Long paymentId;

    /** 链上交易哈希（来自 PaymentCompletedEvent） */
    @Column(name = "chain_tx_hash", length = 128)
    @JsonProperty("chainTxHash")
    private String chainTxHash;

    /** 支付连接器 ID（如 POLYGON、ETHEREUM、STRIPE） */
    @Column(name = "connector_id", length = 32)
    @JsonProperty("connectorId")
    private String connectorId;

    /** 路由决策耗时（毫秒） */
    @Column(name = "routing_latency_ms")
    @JsonProperty("routingLatencyMs")
    private Long routingLatencyMs;

    /** 支付成本（basis points） */
    @Column(name = "cost_bps")
    @JsonProperty("costBps")
    private Integer costBps;

    /** 付款方链上地址（可选） */
    @Column(name = "payer_address", length = 128)
    @JsonProperty("payerAddress")
    private String payerAddress;

    /** 收款方链上地址（可选） */
    @Column(name = "payee_address", length = 128)
    @JsonProperty("payeeAddress")
    private String payeeAddress;

    /** 结算链上交易哈希（settle 成功后由 DefaultClearingEngine 回填。
     *  与原支付的 chainTxHash 区分：一笔支付在其自身的链上完成，
     *  结算转账是独立的第二笔链上交易） */
    @Column(name = "settlement_tx_hash", length = 128)
    @JsonProperty("settlementTxHash")
    private String settlementTxHash;

    /** 订单状态枚举 */
    public enum OrderStatus {
        PENDING,
        SETTLED,
        FAILED
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getSettlementCycle() { return settlementCycle; }
    public void setSettlementCycle(String settlementCycle) { this.settlementCycle = settlementCycle; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }

    public String getChainTxHash() { return chainTxHash; }
    public void setChainTxHash(String chainTxHash) { this.chainTxHash = chainTxHash; }

    public String getConnectorId() { return connectorId; }
    public void setConnectorId(String connectorId) { this.connectorId = connectorId; }

    public Long getRoutingLatencyMs() { return routingLatencyMs; }
    public void setRoutingLatencyMs(Long routingLatencyMs) { this.routingLatencyMs = routingLatencyMs; }

    public Integer getCostBps() { return costBps; }
    public void setCostBps(Integer costBps) { this.costBps = costBps; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public String getPayeeAddress() { return payeeAddress; }
    public void setPayeeAddress(String payeeAddress) { this.payeeAddress = payeeAddress; }

    public String getSettlementTxHash() { return settlementTxHash; }
    public void setSettlementTxHash(String settlementTxHash) { this.settlementTxHash = settlementTxHash; }
}