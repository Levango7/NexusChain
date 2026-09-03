package org.nexus.analytics.projection;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付读模型实体（CQRS 查询侧）。
 *
 * <p>由 {@link PaymentProjection} 消费 {@code payment-events} Kafka topic 后投影得到，
 * 仅供 {@link PaymentQueryService} 查询使用，不参与命令侧事务。
 *
 * <p>本类为纯 POJO（不绑定 JPA），由 {@link PaymentQueryService} 内部存储管理。
 * 若后续需要持久化读模型，可加 {@code @Entity} 注解并配合仓储层，
 * 但本任务范围内使用内存存储以避免引入数据库 schema 变更。
 *
 * <p>字段含义与命令侧 {@code PaymentOrder} 对齐，但状态枚举使用事件溯源视角
 * （CREATED/PROCESSING/SUCCEEDED/FAILED/REFUNDED）。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
public class PaymentReadModel {

    /** 读模型状态枚举（与 PaymentAggregate.State 对齐） */
    public enum State {
        CREATED, PROCESSING, SUCCEEDED, FAILED, REFUNDED
    }

    /** 聚合根 ID（即支付订单 ID 字符串形式） */
    private String aggregateId;
    /** 商户 ID */
    private Long merchantId;
    /** 订单号 */
    private String orderNo;
    /** 支付金额 */
    private BigDecimal amount;
    /** 币种符号 */
    private String tokenSymbol;
    /** 付款方地址 */
    private String payerAddress;
    /** 收款方地址 */
    private String payeeAddress;
    /** 链上交易哈希 */
    private String chainTxHash;
    /** 当前状态 */
    private State state;
    /** 已结算金额（成功后填充） */
    private BigDecimal settledAmount;
    /** 支付完成时间 */
    private Instant paidAt;
    /** 失败原因码 */
    private String failureCode;
    /** 失败详情 */
    private String failureMessage;
    /** 路由决策耗时（毫秒） */
    private Long routingLatencyMs;
    /** 支付成本（basis points） */
    private Integer costBps;
    /** 退款单号 */
    private String refundNo;
    /** 退款金额 */
    private BigDecimal refundAmount;
    /** 退款链上交易哈希 */
    private String refundChainTxHash;
    /** 退款原因 */
    private String refundReason;
    /** 聚合根版本号（最近一次投影的事件版本） */
    private long version;
    /** 读模型最后更新时间 */
    private Instant updatedAt;

    public PaymentReadModel() {
    }

    public static PaymentReadModel fromCreated(String aggregateId, Long merchantId, String orderNo,
                                               BigDecimal amount, String tokenSymbol,
                                               String payerAddress, String payeeAddress,
                                               long version, Instant occurredAt) {
        PaymentReadModel rm = new PaymentReadModel();
        rm.aggregateId = aggregateId;
        rm.merchantId = merchantId;
        rm.orderNo = orderNo;
        rm.amount = amount;
        rm.tokenSymbol = tokenSymbol;
        rm.payerAddress = payerAddress;
        rm.payeeAddress = payeeAddress;
        rm.state = State.CREATED;
        rm.version = version;
        rm.updatedAt = occurredAt;
        return rm;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(Long merchantId) {
        this.merchantId = merchantId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getTokenSymbol() {
        return tokenSymbol;
    }

    public void setTokenSymbol(String tokenSymbol) {
        this.tokenSymbol = tokenSymbol;
    }

    public String getPayerAddress() {
        return payerAddress;
    }

    public void setPayerAddress(String payerAddress) {
        this.payerAddress = payerAddress;
    }

    public String getPayeeAddress() {
        return payeeAddress;
    }

    public void setPayeeAddress(String payeeAddress) {
        this.payeeAddress = payeeAddress;
    }

    public String getChainTxHash() {
        return chainTxHash;
    }

    public void setChainTxHash(String chainTxHash) {
        this.chainTxHash = chainTxHash;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public void setSettledAmount(BigDecimal settledAmount) {
        this.settledAmount = settledAmount;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(String failureCode) {
        this.failureCode = failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public Long getRoutingLatencyMs() {
        return routingLatencyMs;
    }

    public void setRoutingLatencyMs(Long routingLatencyMs) {
        this.routingLatencyMs = routingLatencyMs;
    }

    public Integer getCostBps() {
        return costBps;
    }

    public void setCostBps(Integer costBps) {
        this.costBps = costBps;
    }

    public String getRefundNo() {
        return refundNo;
    }

    public void setRefundNo(String refundNo) {
        this.refundNo = refundNo;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(BigDecimal refundAmount) {
        this.refundAmount = refundAmount;
    }

    public String getRefundChainTxHash() {
        return refundChainTxHash;
    }

    public void setRefundChainTxHash(String refundChainTxHash) {
        this.refundChainTxHash = refundChainTxHash;
    }

    public String getRefundReason() {
        return refundReason;
    }

    public void setRefundReason(String refundReason) {
        this.refundReason = refundReason;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "PaymentReadModel{" +
                "aggregateId='" + aggregateId + '\'' +
                ", state=" + state +
                ", version=" + version +
                ", orderNo='" + orderNo + '\'' +
                '}';
    }
}
