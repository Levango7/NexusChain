package org.nexus.gateway.config;

import org.nexus.gateway.dto.ApiResponse;
import org.nexus.gateway.dto.ErrorCode;
import org.nexus.gateway.security.MerchantOwnershipException;
import org.nexus.gateway.util.LogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Bad request: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException e) {
        log.warn("State conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ErrorCode.ILLEGAL_STATE_TRANSITION.getCode(), e.getMessage()));
    }

    @ExceptionHandler(MerchantOwnershipException.class)
    public ResponseEntity<ApiResponse<Void>> handleOwnership(MerchantOwnershipException e) {
        log.warn("Ownership violation: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.RESOURCE_NOT_OWNED.getCode(),
                        ErrorCode.RESOURCE_NOT_OWNED.getMessage()));
    }

    /**
     * 方法安全层（@PreAuthorize）拒绝：403（2026-09-03 死端点修复配套）。
     *
     * <p>无此 handler 时 AccessDeniedException 会落入下方 Exception 兜底
     * 被报成 500 —— 鉴权失败被伪装成服务器内部错误，误导监控与调用方。</p>
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException e) {
        log.warn("Access denied by method security: {}", e.getMessage());
        // B7（2026-09-11 质量审查）：业务码与 HTTP 状态对齐——403 配
        // ACCESS_DENIED(40301)（原 BAD_REQUEST(40000) 错位）
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error(ErrorCode.ACCESS_DENIED.getCode(), ErrorCode.ACCESS_DENIED.getMessage()));
    }

    /**
     * 方法不允许（405，P1 修复 2026-10-11）。
     *
     * <p>此前未显式处理：{@code HttpRequestMethodNotSupportedException} 被下面的兜底
     * {@code Exception} 处理器吞成 **500**——语义错误（"只读端点被 POST"应回 405，
     * 让调用方明确知道是方法问题，而不是"服务端炸了"）。</p>
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException e) {
        log.warn("Method not allowed: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), "method not allowed"));
    }

    /**
     * Bean Validation 失败（{@code @Valid @RequestBody} 校验不过）→ 400（B-13 修复）。
     *
     * <p><b>暴露途径</b>：2026-11-16「API 层真实攻击载荷电池」实测——此前的 handler
     * 集合里没有 {@code MethodArgumentNotValidException}，于是 {@code amount=0}、
     * {@code amount=-1}、{@code description} 超长这类<b>形态错误</b>全部落到下方
     * {@code Exception} 兜底，被报成 <b>500</b>（语义错误 + 让调用方以为服务端故障）。</p>
     *
     * <p><b>响应不回显明细</b>：Spring 的默认异常消息里含「被拒的字段值」，
     * 原样回显等于把攻击者输入反射回响应体、也便于构造 XSS/探测载荷；
     * 明细只写日志（经 {@link LogSanitizer#stripControlChars(String)} 中和，
     * 防 CRLF 伪造日志行），响应统一给通用文案。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationFailure(MethodArgumentNotValidException e) {
        int fieldCount = e.getBindingResult() == null ? 0 : e.getBindingResult().getFieldErrorCount();
        log.warn("Validation failed: {} field error(s) [{}]", fieldCount,
                LogSanitizer.stripControlChars(e.getMessage()));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_PARAMS.getCode(), ErrorCode.INVALID_PARAMS.getMessage()));
    }

    /**
     * 请求体不可读（畸形 JSON / JSON 类型混淆 / 数字越界）→ 400（B-13 修复）。
     *
     * <p>与上一条同源：{@code HttpMessageNotReadableException} 也没有 handler，
     * 于是「截断 JSON」「{@code amount} 传成字符串/数组/对象」「{@code expiryMinutes}
     * 传 {@code 2147483648}」全被报成 500。响应同样只给通用文案——Jackson 的
     * 消息里常含<b>攻击者输入的片段</b>（类名/位置描述+原始值），不回显。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("Unreadable request body [{}]", LogSanitizer.stripControlChars(e.getMessage()));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_PARAMS.getCode(), ErrorCode.INVALID_PARAMS.getMessage()));
    }

    /**
     * 缺少必需查询参数（{@code MissingServletRequestParameterException}）→ 400。
     * 与上两条同族：形态错误不应伪装成 500。
     */
    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException e) {
        log.warn("Missing request parameter [{}]", LogSanitizer.stripControlChars(e.getMessage()));
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.INVALID_PARAMS.getCode(), ErrorCode.INVALID_PARAMS.getMessage()));
    }

    /**
     * 不支持的 Content-Type / 媒体类型 → 415。
     */
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(
            org.springframework.web.HttpMediaTypeNotSupportedException e) {
        log.warn("Unsupported media type [{}]", LogSanitizer.stripControlChars(e.getMessage()));
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error(ErrorCode.INVALID_PARAMS.getCode(), ErrorCode.INVALID_PARAMS.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage()));
    }
}