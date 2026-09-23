package org.nexus.gateway.apikey;

/**
 * API Key 权限范围枚举。
 *
 * <p>定义 API Key 可授权的操作范围，支持多 scope 组合授权。
 * {@code ALL} 表示拥有全部权限，等同于其他所有 scope 的并集。</p>
 */
public enum ApiKeyScope {

    /** 支付操作权限：创建支付、查询支付状态等。 */
    PAYMENTS,

    /** 退款操作权限：发起退款、查询退款状态等。 */
    REFUNDS,

    /** Webhook 配置与接收权限：注册/更新 webhook 端点、接收回调通知。 */
    WEBHOOKS,

    /** 分账操作权限：创建分账规则、查询分账结果等。 */
    SPLITS,

    /** 结算操作权限：查询结算记录、发起结算请求等。 */
    SETTLEMENTS,

    /** Dashboard 访问权限：查询统计概览、交易报表等。 */
    DASHBOARD,

    /** 全部权限：等同于所有其他 scope 的并集。 */
    ALL
}