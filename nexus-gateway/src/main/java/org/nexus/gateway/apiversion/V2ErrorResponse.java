package org.nexus.gateway.apiversion;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * v2 API 统一错误响应（P4-T7）。
 *
 * <p>所有 v2 端点的错误响应遵循此结构，替代 v1 的 {@code {code, message, data, traceId}} 平铺格式：</p>
 * <pre>{@code
 * {
 *   "error": {
 *     "code": "ORDER_NOT_FOUND",
 *     "message": "Order with id=123 not found",
 *     "details": { "orderId": 123 },
 *     "traceId": "a1b2c3d4e5f6a7b8"
 *   }
 * }
 * }</pre>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>{@code code}：稳定不变的字符串错误码（机器可读），与 v1 数值码并存</li>
 *   <li>{@code message}：人类可读的错误描述</li>
 *   <li>{@code details}：可选的结构化补充信息（字段级错误、约束详情等）</li>
 *   <li>{@code traceId}：分布式追踪 ID，与 Micrometer Tracing 对齐</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class V2ErrorResponse {

    private final ErrorBody error;

    public V2ErrorResponse(ErrorBody error) {
        this.error = error;
    }

    /**
     * 快速构造错误响应。
     *
     * @param code    稳定错误码（如 "ORDER_NOT_FOUND"）
     * @param message 人类可读消息
     */
    public static V2ErrorResponse of(String code, String message) {
        return new V2ErrorResponse(new ErrorBody(code, message, null, newTraceId()));
    }

    /**
     * 带详情构造错误响应。
     *
     * @param code    稳定错误码
     * @param message 人类可读消息
     * @param details 结构化补充信息
     */
    public static V2ErrorResponse of(String code, String message, Map<String, Object> details) {
        return new V2ErrorResponse(new ErrorBody(code, message, details, newTraceId()));
    }

    /**
     * 带指定 traceId 构造错误响应（测试用）。
     */
    public static V2ErrorResponse of(String code, String message, Map<String, Object> details, String traceId) {
        return new V2ErrorResponse(new ErrorBody(code, message, details, traceId));
    }

    /**
     * 取当前请求的真实追踪 ID。
     *
     * <p>P1 #22a（2026-09-21 修复）：此前恒为 {@code UUID.randomUUID()}，
     * 而本类 javadoc 却声称 traceId「与 Micrometer Tracing 对齐」——
     * <b>文档与实现不符</b>。调用方拿该 ID 在链路系统中查不到任何东西。
     * 现改为读取 MDC 中的真实 traceId，仅在无 tracing 上下文时回退。</p>
     */
    private static String newTraceId() {
        return org.nexus.gateway.util.TraceIdSupport.currentTraceId();
    }

    public ErrorBody getError() {
        return error;
    }

    /** 错误体 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class ErrorBody {
        private final String code;
        private final String message;
        private final Map<String, Object> details;
        private final String traceId;

        public ErrorBody(String code, String message, Map<String, Object> details, String traceId) {
            this.code = code;
            this.message = message;
            this.details = details;
            this.traceId = traceId;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        public Map<String, Object> getDetails() {
            return details;
        }

        public String getTraceId() {
            return traceId;
        }
    }
}