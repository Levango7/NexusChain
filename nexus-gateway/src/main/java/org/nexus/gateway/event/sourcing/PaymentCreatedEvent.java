package org.nexus.gateway.event.sourcing;

import java.math.BigDecimal;

/**
 * 支付创建事件。
 *
 * <p>当商户发起一笔支付订单并进入支付流程时产出本事件。
 * 聚合根应用本事件后状态变为 {@code CREATED}。
 *
 * <p>对应 {@code PaymentOrder.OrderStatus#PENDING}（订单已创建待支付）。
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
public class PaymentCreatedEvent extends PaymentEvent {

    private static final long serialVersionUID = 1L;

    /** 商户 ID */
    private final Long merchantId;
    /** 订单号（业务可见编号） */
    private final String orderNo;
    /** 支付金额（最小单位） */
    private final BigDecimal amount;
    /** 币种符号（如 NEX） */
    private final String tokenSymbol;
    /** 付款方地址 */
    private final String payerAddress;
    /** 收款方地址 */
    private final String payeeAddress;

    public PaymentCreatedEvent(String aggregateId, long version, Long merchantId, String orderNo,
                               BigDecimal amount, String tokenSymbol, String payerAddress, String payeeAddress) {
        super(aggregateId, version);
        this.merchantId = merchantId;
        this.orderNo = orderNo;
        this.amount = amount;
        this.tokenSymbol = tokenSymbol;
        this.payerAddress = payerAddress;
        this.payeeAddress = payeeAddress;
    }

    public PaymentCreatedEvent(String eventId, String aggregateId, java.time.Instant timestamp, long version,
                               Long merchantId, String orderNo, BigDecimal amount, String tokenSymbol,
                               String payerAddress, String payeeAddress) {
        super(eventId, aggregateId, timestamp, version);
        this.merchantId = merchantId;
        this.orderNo = orderNo;
        this.amount = amount;
        this.tokenSymbol = tokenSymbol;
        this.payerAddress = payerAddress;
        this.payeeAddress = payeeAddress;
    }

    @Override
    public String getEventType() {
        return "PAYMENT_CREATED";
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getTokenSymbol() {
        return tokenSymbol;
    }

    public String getPayerAddress() {
        return payerAddress;
    }

    public String getPayeeAddress() {
        return payeeAddress;
    }
}