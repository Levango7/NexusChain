package org.nexus.gateway.apiversion;

/**
 * v2 API 稳定错误码枚举（P4-T7）。
 *
 * <p>字符串错误码，机器可读、跨版本稳定。与 v1 的 {@link org.nexus.gateway.dto.ErrorCode}
 * 数值码并存（v1 保留数值码以维持向后兼容）。</p>
 *
 * <p>命名约定：{@code <RESOURCE>_<REASON>}，全大写下划线分隔。</p>
 */
public enum V2ErrorCode {

    // === 4xx 客户端错误 ===
    BAD_REQUEST("BAD_REQUEST", 400, "请求格式错误或参数校验失败"),
    INVALID_CURSOR("INVALID_CURSOR", 400, "游标无效或已过期"),
    INVALID_FIELDS("INVALID_FIELDS", 400, "fields 参数包含未知字段"),
    INVALID_PAGE_SIZE("INVALID_PAGE_SIZE", 400, "pageSize 必须为 1-100 之间的整数"),

    UNAUTHORIZED("UNAUTHORIZED", 401, "未提供有效的认证凭证"),
    MISSING_API_KEY("MISSING_API_KEY", 401, "缺少 X-NexusChain-ApiKey 头"),
    INVALID_API_KEY("INVALID_API_KEY", 401, "API Key 无效或已吊销"),

    FORBIDDEN("FORBIDDEN", 403, "无权访问该资源"),
    MERCHANT_NOT_VERIFIED("MERCHANT_NOT_VERIFIED", 403, "商户未完成 KYC 验证"),
    RISK_REJECTED("RISK_REJECTED", 403, "支付被风控拒绝"),
    COMPLIANCE_REJECTED("COMPLIANCE_REJECTED", 403, "支付被合规筛查拒绝"),

    NOT_FOUND("NOT_FOUND", 404, "资源不存在"),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", 404, "订单不存在"),
    MERCHANT_NOT_FOUND("MERCHANT_NOT_FOUND", 404, "商户不存在"),
    PAYMENT_NOT_FOUND("PAYMENT_NOT_FOUND", 404, "支付不存在"),

    METHOD_NOT_ALLOWED("METHOD_NOT_ALLOWED", 405, "HTTP 方法不被允许"),

    CONFLICT("CONFLICT", 409, "资源状态冲突"),
    ILLEGAL_STATE_TRANSITION("ILLEGAL_STATE_TRANSITION", 409, "非法状态转换"),
    ORDER_ALREADY_PAID("ORDER_ALREADY_PAID", 409, "订单已支付"),
    ORDER_EXPIRED("ORDER_EXPIRED", 409, "订单已过期"),

    UNSUPPORTED_VERSION("UNSUPPORTED_VERSION", 422, "不支持的 API 版本"),

    RATE_LIMIT_EXCEEDED("RATE_LIMIT_EXCEEDED", 429, "请求频率超限"),

    // === 5xx 服务端错误 ===
    INTERNAL_ERROR("INTERNAL_ERROR", 500, "服务内部错误"),
    NOT_IMPLEMENTED("NOT_IMPLEMENTED", 501, "功能尚未实现"),

    BAD_GATEWAY("BAD_GATEWAY", 502, "上游服务不可用"),
    CHAIN_NODE_UNAVAILABLE("CHAIN_NODE_UNAVAILABLE", 502, "链节点不可用"),
    WALLET_SERVICE_UNAVAILABLE("WALLET_SERVICE_UNAVAILABLE", 502, "钱包服务不可用"),
    SIGNING_SERVICE_UNAVAILABLE("SIGNING_SERVICE_UNAVAILABLE", 502, "签名服务不可用"),

    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", 503, "服务暂时不可用");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    V2ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}