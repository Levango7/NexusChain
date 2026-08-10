package org.nexus.gateway.event.sourcing;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 支付成功事件。
 *
 * <p>当链上交易达到确认阈值并通过 AML 复核后产出本事件。
 * 聚合根应用本事件后状态变为 {@code SUCCEEDED}。
 *
 * <p>对应 {@code PaymentOrder.OrderStatus#PAID}（已支付）。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
public class PaymentSucceededEvent extends PaymentEvent {

    private static final long serialVersionUID = 1L;

    /** 链上交易哈希 */
    private final String chainTxHash;
    /** 实际结算金额（与创建金额可能因汇率/精度有差异） */
    private final BigDecimal settledAmount;
    /** 支付完成时间（链上确认时间） */
    private final Instant paidAt;

    public PaymentSucceededEvent(String aggregateId, long version, String chainTxHash,
                                 BigDecimal settledAmount, Instant paidAt) {
        super(aggregateId, version);
        this.chainTxHash = chainTxHash;
        this.settledAmount = settledAmount;
        this.paidAt = paidAt;
    }

    public PaymentSucceededEvent(String eventId, String aggregateId, Instant timestamp, long version,
                                 String chainTxHash, BigDecimal settledAmount, Instant paidAt) {
        super(eventId, aggregateId, timestamp, version);
        this.chainTxHash = chainTxHash;
        this.settledAmount = settledAmount;
        this.paidAt = paidAt;
    }

    @Override
    public String getEventType() {
        return "PAYMENT_SUCCEEDED";
    }

    public String getChainTxHash() {
        return chainTxHash;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public Instant getPaidAt() {
        return paidAt;
    }
}