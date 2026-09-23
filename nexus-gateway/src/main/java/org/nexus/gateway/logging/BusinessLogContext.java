package org.nexus.gateway.logging;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 业务日志上下文（任务 #28）。
 *
 * <p>使用 SLF4J MDC（Mapped Diagnostic Context）注入业务上下文字段，
 * 使所有日志输出自动携带业务标识，便于日志检索和链路追踪关联。</p>
 *
 * <p>注入的 MDC 字段：</p>
 * <ul>
 *   <li>{@code merchantId} — 商户 ID</li>
 *   <li>{@code orderId} — 订单 ID</li>
 *   <li>{@code paymentId} — 支付 ID</li>
 *   <li>{@code connectorType} — 连接器类型（stripe/adyen/wechat/alipay 等）</li>
 *   <li>{@code traceId} — 链路追踪 ID（与 Micrometer Tracing 集成）</li>
 * </ul>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * businessLogContext.setContext("M001", "ORD123", "PAY456", "stripe");
 * try {
 *     // 业务逻辑，所有日志自动携带 MDC 字段
 *     log.info("Processing payment");
 * } finally {
 *     businessLogContext.clearContext();
 * }
 * }</pre>
 *
 * <p>与 {@code BusinessSpan} 集成：traceId 由 Micrometer Tracing 自动注入 MDC，
 * 本类不覆盖 traceId，仅在需要时补充设置。</p>
 */
@Component
public class BusinessLogContext {

    /** MDC 字段名常量 */
    public static final String MERCHANT_ID = "merchantId";
    public static final String ORDER_ID = "orderId";
    public static final String PAYMENT_ID = "paymentId";
    public static final String CONNECTOR_TYPE = "connectorType";
    public static final String TRACE_ID = "traceId";

    /**
     * 设置业务上下文到 MDC。
     *
     * <p>将所有业务字段写入 MDC，使后续日志输出自动携带这些字段。
     * 已存在的 MDC 值会被覆盖。</p>
     *
     * @param merchantId    商户 ID（可为 null，则不设置）
     * @param orderId       订单 ID（可为 null，则不设置）
     * @param paymentId     支付 ID（可为 null，则不设置）
     * @param connectorType 连接器类型（可为 null，则不设置）
     */
    public void setContext(String merchantId, String orderId, String paymentId, String connectorType) {
        if (merchantId != null) {
            MDC.put(MERCHANT_ID, merchantId);
        }
        if (orderId != null) {
            MDC.put(ORDER_ID, orderId);
        }
        if (paymentId != null) {
            MDC.put(PAYMENT_ID, paymentId);
        }
        if (connectorType != null) {
            MDC.put(CONNECTOR_TYPE, connectorType);
        }
    }

    /**
     * 设置单个业务字段到 MDC。
     *
     * @param key   字段名（使用本类的常量）
     * @param value 字段值（null 时移除该字段）
     */
    public void setField(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        } else {
            MDC.remove(key);
        }
    }

    /**
     * 从 MDC 获取业务字段值。
     *
     * @param key 字段名（使用本类的常量）
     * @return 字段值，不存在时返回 null
     */
    public String getField(String key) {
        return MDC.get(key);
    }

    /**
     * 获取当前所有业务上下文字段的快照。
     *
     * @return 包含所有业务字段的 Map（空值不包含）
     */
    public Map<String, String> getContext() {
        Map<String, String> context = new HashMap<>();
        putIfNotNull(context, MERCHANT_ID, MDC.get(MERCHANT_ID));
        putIfNotNull(context, ORDER_ID, MDC.get(ORDER_ID));
        putIfNotNull(context, PAYMENT_ID, MDC.get(PAYMENT_ID));
        putIfNotNull(context, CONNECTOR_TYPE, MDC.get(CONNECTOR_TYPE));
        putIfNotNull(context, TRACE_ID, MDC.get(TRACE_ID));
        return context;
    }

    /**
     * 清理所有业务上下文字段。
     *
     * <p>从 MDC 中移除所有由本类设置的业务字段。
     * traceId 由 Micrometer Tracing 管理，不在此清理。</p>
     */
    public void clearContext() {
        MDC.remove(MERCHANT_ID);
        MDC.remove(ORDER_ID);
        MDC.remove(PAYMENT_ID);
        MDC.remove(CONNECTOR_TYPE);
    }

    private void putIfNotNull(Map<String, String> map, String key, String value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}