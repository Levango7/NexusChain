package org.nexus.oracle.governance.execution;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;

import java.lang.reflect.Method;

/**
 * 治理异步执行未捕获异常处理器（GOV-P2-02）。
 *
 * <p>当 {@code @Async} 方法抛出未被内部 try-catch 捕获的异常时，
 * Spring 默认仅通过 {@code SimpleAsyncUncaughtExceptionHandler} 记录 WARN 日志，
 * 异常实际上被"吞掉"，无法触发告警或状态回写。
 *
 * <p>此实现将异常记录为 ERROR 级别日志（含方法名、参数、完整堆栈），
 * 确保运维能通过日志监控发现异步执行失败。
 *
 * <p>注册方式：在 {@code @Configuration} 类中重写
 * {@code AsyncConfigurer#getAsyncUncaughtExceptionHandler()} 返回此实现。
 *
 * <pre>
 * {@code @Configuration}
 * {@code @EnableAsync}
 * public class AsyncConfig implements AsyncConfigurer {
 *     {@code @Override}
 *     public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
 *         return new GovernanceAsyncUncaughtExceptionHandler();
 *     }
 * }
 * </pre>
 *
 * @since 2.1.0
 */
@Slf4j
public class GovernanceAsyncUncaughtExceptionHandler implements AsyncUncaughtExceptionHandler {

    /**
     * 处理 {@code @Async} 方法未捕获的异常。
     *
     * <p>记录 ERROR 级别日志，包含：
     * <ul>
     *   <li>异常消息与堆栈</li>
     *   <li>抛出异常的方法签名</li>
     *   <li>方法参数（脱敏后）</li>
     * </ul>
     *
     * @param ex        未捕获的异常
     * @param method    抛出异常的异步方法
     * @param params    方法参数
     */
    @Override
    public void handleUncaughtException(Throwable ex, Method method, Object... params) {
        // GOV-P2-01: 脱敏异常信息
        String sanitizedMessage = ErrorMessageSanitizer.sanitizeErrorMessage(ex);

        log.error("GOV-P2-02: Uncaught exception in @Async governance method: method={}, params={}, error={}",
                method == null ? "null" : method.toGenericString(),
                sanitizeParams(params),
                sanitizedMessage,
                ex);

        // 完整异常堆栈在 DEBUG 级别记录
        log.debug("GOV-P2-02: Full exception details for @Async method: {}", method, ex);
    }

    /**
     * 脱敏方法参数（GOV-P2-01）。
     *
     * @param params 原始参数数组
     * @return 脱敏后的参数字符串表示
     */
    private String sanitizeParams(Object[] params) {
        if (params == null || params.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String paramStr = String.valueOf(params[i]);
            sb.append(ErrorMessageSanitizer.sanitizeErrorMessage(paramStr));
        }
        sb.append("]");
        return sb.toString();
    }
}