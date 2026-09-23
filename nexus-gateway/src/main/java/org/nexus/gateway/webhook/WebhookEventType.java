package org.nexus.gateway.webhook;

/**
 * Webhook 订阅事件类型枚举。
 *
 * <p>定义商户可订阅的所有支付/风控/结算事件类型。商户在创建 WebhookSubscription
 * 时选择需要订阅的事件类型，投递服务根据事件类型匹配活跃订阅并投递回调。</p>
 *
 * <ul>
 *   <li>{@code PAYMENT_CONFIRMED} — 支付确认（链上达到足够确认数）</li>
 *   <li>{@code PAYMENT_FAILED} — 支付失败（链上交易失败或超时）</li>
 *   <li>{@code REFUND_ISSUED} — 退款已发起</li>
 *   <li>{@code SETTLEMENT_COMPLETED} — 结算完成</li>
 *   <li>{@code SPLIT_COMPLETED} — 分账完成</li>
 *   <li>{@code RISK_ALERT} — 风控告警</li>
 *   <li>{@code SUBSCRIPTION_CHARGED} — 订阅周期扣款成功</li>
 *   <li>{@code SUBSCRIPTION_FAILED} — 订阅周期扣款失败</li>
 * </ul>
 */
public enum WebhookEventType {

    /** 支付确认：链上达到足够确认数，订单标记为已支付。 */
    PAYMENT_CONFIRMED,

    /** 支付失败：链上交易失败或超时未确认。 */
    PAYMENT_FAILED,

    /** 退款已发起：退款交易已提交到链上。 */
    REFUND_ISSUED,

    /** 结算完成：资金结算流程完成。 */
    SETTLEMENT_COMPLETED,

    /** 分账完成：分账交易已执行完毕。 */
    SPLIT_COMPLETED,

    /** 风控告警：风控系统检测到异常交易或行为。 */
    RISK_ALERT,

    /** 订阅周期扣款成功：定期扣款交易确认成功。 */
    SUBSCRIPTION_CHARGED,

    /** 订阅周期扣款失败：定期扣款交易失败。 */
    SUBSCRIPTION_FAILED
}