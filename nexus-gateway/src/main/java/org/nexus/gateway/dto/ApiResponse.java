package org.nexus.gateway.dto;

import java.util.UUID;

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

    /** Distributed trace ID for cross-module debugging */
    private String traceId;

    public ApiResponse() {
        this.traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
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