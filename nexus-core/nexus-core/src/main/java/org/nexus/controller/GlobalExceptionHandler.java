package org.nexus.controller;

import org.nexus.ApiResult.APIResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * nexus-core 全局异常处理器（2026-09-11 质量审查 B2a）。
 *
 * <p>背景：core 的 CommandController 此前无全局兜底——异常直落 Spring 默认
 * error 页（{timestamp,status,error,path}），与端点既有的
 * {@code APIResult{code,message,data}} 业务格式割裂；getBlock 抛异常后静默
 * 回退到 handleGetBlockByHash（吞掉原始错误）。</p>
 *
 * <p>兼容性设计（不破坏既有契约）：错误统一为 {@code APIResult.newFailResult}
 * 形状（code=5000 与 APIResult.FAIL 既有约定一致）——调用方解析
 * code/message 的逻辑不受影响；成功路径完全不动。</p>
 *
 * <p>注：core 是链内核管理面（19585），调用方为网关/signing 等内部服务，
 * 参数类错误纠正为 400，内部异常 500 + 服务端日志（响应不带堆栈）。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 参数缺失（400）。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<APIResult<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(APIResult.newFailResult(4000, "Missing parameter: " + e.getParameterName(), null));
    }

    /** 参数非法（400）：此前部分端点直接抛给默认处理变 500。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<APIResult<Void>> handleIllegalArg(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(APIResult.newFailResult(4000, e.getMessage(), null));
    }

    /** 兜底（500）：堆栈只进日志，APIResult 保持 code/message 简洁形状。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<APIResult<Void>> handleGeneric(Exception e) {
        log.error("Unhandled exception in nexus-core", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(APIResult.newFailResult(APIResult.FAIL, "Internal error", null));
    }
}
