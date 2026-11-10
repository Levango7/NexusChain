package org.nexus.gateway.voidreversal;

import org.nexus.gateway.event.PaymentEvent;

import java.math.BigDecimal;

/**
 * 订单冲正事件 — 冲正成功后发布，通知下游模块订单已被冲正。
 *
 * <p>继承 {@link PaymentEvent}，复用 orderId/orderNo/merchantId 基础字段，
 * 额外携带冲正编号和冲正后商户余额信息。</p>
 *
 * <p>消费方：对账模块、通知模块等（只读）。本事件是冲正请求已受理的通知，
 * <b>不代表钱已动</b>；当前无冲正记账能力，清算模块不据此生成反向清算记录
 * （板面 20261110-1800 裁决 Q3；冲正记账属产品缺口，单独立项）。</p>
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