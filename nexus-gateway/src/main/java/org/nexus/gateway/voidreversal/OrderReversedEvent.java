package org.nexus.gateway.voidreversal;

import org.nexus.gateway.event.PaymentEvent;

import java.math.BigDecimal;

/**
 * 订单冲正事件 — 冲正成功后发布，通知下游模块订单已被冲正。
 *
 * <p>继承 {@link PaymentEvent}，复用 orderId/orderNo/merchantId 基础字段，
 * 额外携带冲正编号和冲正后商户余额信息。</p>
 *
 * <p>消费方：清算模块（冲正后生成反向清算记录）、对账模块、通知模块等。</p>
 */
public class OrderReversedEvent extends PaymentEvent {

    private final String reversalNo;
    private final BigDecimal amount;
    private final BigDecimal balanceAfter;

    public OrderReversedEvent(Object source, Long orderId, String orderNo, Long merchantId,
                               String reversalNo, BigDecimal amount, BigDecimal balanceAfter) {
        super(source, orderId, orderNo, merchantId);
        this.reversalNo = reversalNo;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    @Override
    public String getEventType() { return "ORDER_REVERSED"; }

    public String getReversalNo() { return reversalNo; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
}