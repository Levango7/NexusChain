package org.nexus.gateway.dto;

/**
 * Unified API response envelope.
 * All gateway endpoints return this structure for consistent client handling.
 *
 * @param <T> payload type
 */
public class ApiResponse<T> {

    /** Business status code (0 = success, 4xxxx = client error, 5xxxx = server error) */
    private int code;

    /** Human-readable message */
    private String message;

    /** Response payload */
    private T data;

    /**
     * 分布式追踪 ID，用于跨模块排查。
     *
     * <p>P1 #22a（2026-09-21 修复）：此前恒为 {@code UUID.randomUUID()}，
     * 与链路系统无任何关联 —— 调用方拿它在日志里<b>查不到东西</b>。
     * 现改为读取 MDC 中的真实 traceId（见 {@link org.nexus.gateway.util.TraceIdSupport}），
     * 仅在无 tracing 上下文时回退为随机关联 ID。</p>
     */
    private String traceId;

    public ApiResponse() {
        this.traceId = org.nexus.gateway.util.TraceIdSupport.currentTraceId();
    }

    // --- Factory methods ---

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 0;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 0;
        r.message = message;
        r.data = data;
        return r;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.message = message;
        r.data = null;
        return r;
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return error(40400, message);
    }

    public static <T> ApiResponse<T> badRequest(String message) {
        return error(40000, message);
    }

    public static <T> ApiResponse<T> conflict(String message) {
        return error(40900, message);
    }

    // --- Getters and Setters ---

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public T getData() { return data; }
    public void setData(T data) { this.data = data; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
}