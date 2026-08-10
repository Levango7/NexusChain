package org.nexus.sdk.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * v2 API 异常（P4-T7）。
 *
 * <p>封装 v2 统一错误响应 {@code {error: {code, message, details, traceId}}}，
 * 调用方可据 {@link #errorCode()} 做精确错误处理。</p>
 */
public class V2ApiException extends RuntimeException {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int httpStatus;
    private final String errorCode;
    private final String traceId;
    private final Map<String, Object> details;

    public V2ApiException(String message, int httpStatus, String errorCode,
                          String traceId, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.traceId = traceId;
        this.details = null;
    }

    public V2ApiException(String message, int httpStatus, String errorCode,
                          String traceId, Map<String, Object> details) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.traceId = traceId;
        this.details = details;
    }

    /**
     * 从 HTTP 响应构造异常（解析 v2 统一错误格式）。
     */
    public static V2ApiException fromResponse(int statusCode, String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode error = root.get("error");
            if (error != null) {
                String code = error.has("code") ? error.get("code").asText() : "UNKNOWN";
                String message = error.has("message") ? error.get("message").asText() : "Unknown error";
                String traceId = error.has("traceId") ? error.get("traceId").asText() : null;
                Map<String, Object> details = null;
                if (error.has("details") && !error.get("details").isNull()) {
                    details = MAPPER.convertValue(error.get("details"), Map.class);
                }
                return new V2ApiException(message, statusCode, code, traceId, details);
            }
        } catch (Exception ignored) {
            // 解析失败时降级为通用错误
        }
        return new V2ApiException("HTTP " + statusCode + ": " + body,
                statusCode, "HTTP_ERROR", null, (Map<String, Object>) null);
    }

    public int httpStatus() { return httpStatus; }
    public String errorCode() { return errorCode; }
    public String traceId() { return traceId; }
    public Map<String, Object> details() { return details; }

    @Override
    public String toString() {
        return "V2ApiException{httpStatus=" + httpStatus
                + ", errorCode='" + errorCode + '\''
                + ", traceId='" + traceId + '\''
                + ", message='" + getMessage() + '\''
                + '}';
    }
}