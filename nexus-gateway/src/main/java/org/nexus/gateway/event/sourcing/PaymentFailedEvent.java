package org.nexus.gateway.event.sourcing;

/**
 * 支付失败事件。
 *
 * <p>当支付流程因风控拒绝、AML 拦截、链上交易失败或订单过期等原因终止时产出本事件。
 * 聚合根应用本事件后状态变为 {@code FAILED}（终态）。
 *
 * <p>对应 {@code PaymentOrder.OrderStatus#FAILED}。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
public class PaymentFailedEvent extends PaymentEvent {

    private static final long serialVersionUID = 1L;

    /** 失败原因码（如 "RISK_REJECTED"、"AML_BLOCKED"、"CHAIN_TIMEOUT"） */
    private final String failureCode;
    /** 失败详情（人类可读） */
    private final String failureMessage;

    public PaymentFailedEvent(String aggregateId, long version, String failureCode, String failureMessage) {
        super(aggregateId, version);
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    public PaymentFailedEvent(String eventId, String aggregateId, java.time.Instant timestamp, long version,
                              String failureCode, String failureMessage) {
        super(eventId, aggregateId, timestamp, version);
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
    }

    @Override
    public String getEventType() {
        return "PAYMENT_FAILED";
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }
}