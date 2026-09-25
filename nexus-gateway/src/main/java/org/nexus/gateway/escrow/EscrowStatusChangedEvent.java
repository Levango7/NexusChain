package org.nexus.gateway.escrow;

import org.nexus.gateway.event.PaymentEvent;

/**
 * 担保交易状态变更事件。
 *
 * <p>当担保交易状态发生变更时发布此事件，供下游模块（清算、对账、通知等）消费。
 * 继承 {@link PaymentEvent} 以复用 orderId/orderNo/merchantId 基础字段。</p>
 */
public class EscrowStatusChangedEvent extends PaymentEvent {

    private final String escrowNo;
    private final EscrowStatus fromStatus;
    private final EscrowStatus toStatus;

    public EscrowStatusChangedEvent(Object source, Long orderId, String orderNo, Long merchantId,
                                     String escrowNo, EscrowStatus fromStatus, EscrowStatus toStatus) {
        super(source, orderId, orderNo, merchantId);
        this.escrowNo = escrowNo;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public String getEscrowNo() { return escrowNo; }
    public EscrowStatus getFromStatus() { return fromStatus; }
    public EscrowStatus getToStatus() { return toStatus; }

    @Override
    public String getEventType() {
        return "ESCROW_STATUS_CHANGED";
    }
}