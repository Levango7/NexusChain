package org.nexus.gateway.event;

import org.springframework.context.ApplicationEvent;

/**
 * Base class for all payment lifecycle events.
 */
public abstract class PaymentEvent extends ApplicationEvent {

    private final Long orderId;
    private final String orderNo;
    private final Long merchantId;

    public PaymentEvent(Object source, Long orderId, String orderNo, Long merchantId) {
        super(source);
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.merchantId = merchantId;
    }

    public Long getOrderId() { return orderId; }
    public String getOrderNo() { return orderNo; }
    public Long getMerchantId() { return merchantId; }

    /** Event type identifier for webhook payloads. */
    public abstract String getEventType();
}