package org.nexus.gateway.event.sourcing;

import java.math.BigDecimal;

/**
 * 支付退款事件。
 *
 * <p>当已支付订单发起退款并完成链上转账后产出本事件。
 * 聚合根应用本事件后状态变为 {@code REFUNDED}（终态）。
 *
 * <p>对应 {@code PaymentOrder.OrderStatus#REFUNDED}。
 *
 * <p>注意：退款流程仍由 Seata AT 强一致事务保护（涉及余额同步扣减），
 * 本事件在 Seata 提交后产出，用于异步通知 analytics / webhook 投影更新。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
public class PaymentRefundedEvent extends PaymentEvent {

    private static final long serialVersionUID = 1L;

    /** 退款单号 */
    private final String refundNo;
    /** 退款金额 */
    private final BigDecimal refundAmount;
    /** 退款链上交易哈希 */
    private final String refundChainTxHash;
    /** 退款原因 */
    private final String reason;

    public PaymentRefundedEvent(String aggregateId, long version, String refundNo, BigDecimal refundAmount,
                                String refundChainTxHash, String reason) {
        super(aggregateId, version);
        this.refundNo = refundNo;
        this.refundAmount = refundAmount;
        this.refundChainTxHash = refundChainTxHash;
        this.reason = reason;
    }

    public PaymentRefundedEvent(String eventId, String aggregateId, java.time.Instant timestamp, long version,
                                String refundNo, BigDecimal refundAmount, String refundChainTxHash, String reason) {
        super(eventId, aggregateId, timestamp, version);
        this.refundNo = refundNo;
        this.refundAmount = refundAmount;
        this.refundChainTxHash = refundChainTxHash;
        this.reason = reason;
    }

    @Override
    public String getEventType() {
        return "PAYMENT_REFUNDED";
    }

    public String getRefundNo() {
        return refundNo;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public String getRefundChainTxHash() {
        return refundChainTxHash;
    }

    public String getReason() {
        return reason;
    }
}