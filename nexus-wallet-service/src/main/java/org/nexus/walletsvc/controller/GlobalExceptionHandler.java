package org.nexus.walletsvc.controller;

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
 * wallet-service 全局异常处理器（2026-09-11 质量审查 B2a）。
 *
 * <p>背景：全模块此前零 @RestControllerAdvice——参数非法（如
 * withdrawal/approve 的非法 approvalId 抛 IllegalArgumentException）落到
 * Spring Boot 默认 error JSON（{timestamp,status,error,path}），与
 * WalletController 既有的裸 Map 业务响应形状完全割裂。</p>
 *
 * <p>兼容性设计（不破坏既有契约）：WalletController 正常路径返回裸
 * Map/实体（无统一信封）——错误响应也保持简单形状
 * {@code {"error": "<message>"}}（与 api-gateway 限流器的
 * {"code","message"} 同族的最小错误体），HTTP 状态码按语义纠正
 * （400/500）。不引入新的信封结构，避免与成功路径割裂加剧。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<Object> error(HttpStatus status, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("error", message);
        return ResponseEntity.status(status).body(payload);
    }

    /** 参数缺失（400）。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Object> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());
        return error(HttpStatus.BAD_REQUEST, "Missing parameter: " + e.getParameterName());
    }

    /** 请求体校验失败（400）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "Validation failed"
                : e.getBindingResult().getFieldErrors().get(0).getField() + ": "
                  + e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        log.warn("Validation failed: {}", msg);
        return error(HttpStatus.BAD_REQUEST, msg);
    }

    /** 参数非法（400）：此前变 500 默认页。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleIllegalArg(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 兜底（500）：堆栈只进服务端日志。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleGeneric(Exception e) {
        log.error("Unhandled exception in wallet-service", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
    }
}
