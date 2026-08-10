package org.nexus.gateway.event.sourcing;

/**
 * 支付处理中事件。
 *
 * <p>当支付订单进入链上广播/确认等待阶段时产出本事件。
 * 聚合根应用本事件后状态变为 {@code PROCESSING}。
 *
 * <p>对应 {@code PaymentOrder.OrderStatus#PAYING}（支付中）。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
public class PaymentProcessingEvent extends PaymentEvent {

    private static final long serialVersionUID = 1L;

    /** 链上交易哈希（如已广播） */
    private final String chainTxHash;
    /** 触发处理的原因（如 "BROADCAST"、"RISK_APPROVED"） */
    private final String reason;

    public PaymentProcessingEvent(String aggregateId, long version, String chainTxHash, String reason) {
        super(aggregateId, version);
        this.chainTxHash = chainTxHash;
        this.reason = reason;
    }

    public PaymentProcessingEvent(String eventId, String aggregateId, java.time.Instant timestamp, long version,
                                  String chainTxHash, String reason) {
        super(eventId, aggregateId, timestamp, version);
        this.chainTxHash = chainTxHash;
        this.reason = reason;
    }

    @Override
    public String getEventType() {
        return "PAYMENT_PROCESSING";
    }

    public String getChainTxHash() {
        return chainTxHash;
    }

    public String getReason() {
        return reason;
    }
}