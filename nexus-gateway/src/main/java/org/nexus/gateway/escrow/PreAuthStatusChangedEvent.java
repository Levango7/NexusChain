package org.nexus.gateway.escrow;

import org.nexus.gateway.event.PaymentEvent;

/**
 * 预授权状态变更事件。
 *
 * <p>当预授权状态发生变更时发布此事件，供下游模块（清算、对账、通知等）消费。
 * 继承 {@link PaymentEvent} 以复用 orderId/orderNo/merchantId 基础字段。</p>
 */
public class PreAuthStatusChangedEvent extends PaymentEvent {

    private final String preauthNo;
    private final PreAuthStatus fromStatus;
    private final PreAuthStatus toStatus;

    public PreAuthStatusChangedEvent(Object source, Long orderId, String orderNo, Long merchantId,
                                      String preauthNo, PreAuthStatus fromStatus, PreAuthStatus toStatus) {
        super(source, orderId, orderNo, merchantId);
        this.preauthNo = preauthNo;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    public String getPreauthNo() { return preauthNo; }
    public PreAuthStatus getFromStatus() { return fromStatus; }
    public PreAuthStatus getToStatus() { return toStatus; }

    @Override
    public String getEventType() {
        return "PREAUTH_STATUS_CHANGED";
    }
}