package org.nexus.signing.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * signing-service 全局异常处理器（2026-09-11 质量审查 B2a）。
 *
 * <p>背景：全模块此前零 @RestControllerAdvice——业务异常直接冒给 Spring 默认
 * 处理，返回 {timestamp,status,error,path} 形状与本模块既有的
 * {statusCode,message,data} 业务响应格式割裂；参数非法抛
 * IllegalArgumentException 会变 500（应为 400）。</p>
 *
 * <p>兼容性设计（不破坏既有契约）：错误响应沿用本模块既有业务形状
 * {@code {"statusCode":5000,"message":"...","data":""}}——与 TxController
 * 的失败响应（HTTP 200 + statusCode 5000）语义一致；但参数类错误按
 * HTTP 语义纠正为 400/405/500 状态码。新调用方可同时依赖 HTTP 状态码
 * 与业务码两层判定，旧调用方解析 body 的逻辑不受影响。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务失败码：与 TxController 既有 fail() 约定一致。 */
    private static final int BIZ_FAIL = 5000;
    /** 参数错误业务码：4xxx 段（400 语义），避免与 5000 业务失败混淆。 */
    private static final int BIZ_BAD_REQUEST = 4000;

    private static ResponseEntity<Object> body(HttpStatus status, int bizCode, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("statusCode", bizCode);
        payload.put("message", message);
        payload.put("data", "");
        return ResponseEntity.status(status).body(payload);
    }

    /** 参数缺失（400）：此前落入 Exception 兜底被报 500。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());
        return body(HttpStatus.BAD_REQUEST, BIZ_BAD_REQUEST, "Missing parameter: " + e.getParameterName());
    }

    /** 请求体校验失败（400）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "Validation failed"
                : e.getBindingResult().getFieldErrors().get(0).getField() + ": "
                  + e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        log.warn("Validation failed: {}", msg);
        return body(HttpStatus.BAD_REQUEST, BIZ_BAD_REQUEST, msg);
    }

    /** 参数非法（400）：TxController 各端点用 IllegalArgumentException 表达参数错。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArg(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return body(HttpStatus.BAD_REQUEST, BIZ_BAD_REQUEST, e.getMessage());
    }

    /** 兜底（500）：完整堆栈进服务端日志，响应只留摘要（不泄漏内部细节）。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGeneric(Exception e) {
        log.error("Unhandled exception in signing-service", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, BIZ_FAIL, "Internal error");
    }
}
