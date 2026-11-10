package org.nexus.gateway.voidreversal;

import org.nexus.gateway.event.PaymentEvent;

import java.math.BigDecimal;

/**
 * 订单撤销事件 — 撤销成功后发布，通知下游模块订单已被撤销。
 *
 * <p>继承 {@link PaymentEvent}，复用 orderId/orderNo/merchantId 基础字段，
 * 额外携带撤销编号和撤销后商户余额信息。</p>
 *
 * <p>消费方：对账模块、通知模块等（只读）。本事件是作废请求已受理的通知，
 * <b>不代表钱已动</b>；当前无冲正记账能力，清算模块不据此做任何资金动作
 * （板面 20261110-1800 裁决 Q3；冲正记账属产品缺口，单独立项）。</p>
 */
public class OrderVoidedEvent extends PaymentEvent {

    private final String voidNo;
    private final BigDecimal amount;
    private final BigDecimal balanceAfter;

    public OrderVoidedEvent(Object source, Long orderId, String orderNo, Long merchantId,
                             String voidNo, BigDecimal amount, BigDecimal balanceAfter) {
        super(source, orderId, orderNo, merchantId);
        this.voidNo = voidNo;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
    }

    @Override
    public String getEventType() { return "ORDER_VOIDED"; }

    public String getVoidNo() { return voidNo; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
}