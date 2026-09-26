package org.nexus.gateway.security.password;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * 支付密码验证拦截器。
 *
 * <p>在 {@link org.nexus.gateway.interceptor.ApiKeyInterceptor} 之后执行，
 * 对支付相关端点（confirm/refund）拦截验证支付密码。</p>
 *
 * <p>验证流程：
 * <ol>
 *   <li>仅对支付确认和退款端点执行密码验证</li>
 *   <li>从请求头 X-Payment-Password 获取密码</li>
 *   <li>商户未设置支付密码时跳过验证（渐进式启用）</li>
 *   <li>密码验证失败返回 40301 PASSWORD_REQUIRED 或 40302 PASSWORD_INVALID</li>
 * </ol></p>
 *
 * <p>来源：设计文档 §6.5.3 PaymentPasswordInterceptor。</p>
 */
@Component
public class PaymentPasswordInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PaymentPasswordInterceptor.class);

    private static final String PAYMENT_PASSWORD_HEADER = "X-Payment-Password";
    private static final String MERCHANT_ID_ATTR = "nexus.merchantId";
    private static final String TENANT_ID_ATTR = "nexus.tenantId";

    /**
     * 需要支付密码验证的端点路径。
     */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/v1/payments/confirm",
            "/api/v1/payments/refund"
    );

    private final PaymentPasswordService passwordService;

    public PaymentPasswordInterceptor(PaymentPasswordService passwordService) {
        this.passwordService = passwordService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();

        // 仅对支付确认和退款端点执行密码验证
        if (!isProtectedPath(path)) {
            return true;
        }

        // 从 ApiKeyInterceptor 设置的请求属性获取商户 ID 和租户 ID
        Long merchantId = resolveMerchantId(request);
        if (merchantId == null) {
            // ApiKeyInterceptor 未通过则不会到达此处，但防御性处理
            return true;
        }
        String tenantId = resolveTenantId(request);

        // 商户未设置支付密码时跳过验证（渐进式启用）
        if (!passwordService.isPasswordSet(merchantId)) {
            return true;
        }

        // 从请求头获取支付密码
        String password = request.getHeader(PAYMENT_PASSWORD_HEADER);
        if (password == null || password.isEmpty()) {
            return reject(response, 40301, "PASSWORD_REQUIRED", "支付密码未提供");
        }

        // 验证密码
        PaymentPasswordService.PasswordVerifyResult result =
                passwordService.verifyPassword(merchantId, password, tenantId);

        switch (result.getStatus()) {
            case SUCCESS -> { return true; }
            case SKIPPED -> { return true; }
            case INVALID -> {
                return reject(response, 40302, "PASSWORD_INVALID",
                        "密码错误，剩余尝试次数: " + result.getRemainingAttempts());
            }
            case LOCKED -> {
                long remainingMinutes = result.getLockedUntil() != null
                        ? ChronoUnit.MINUTES.between(Instant.now(), result.getLockedUntil())
                        : 0;
                return reject(response, 40302, "PASSWORD_LOCKED",
                        "密码锁定，剩余锁定时间: " + remainingMinutes + " 分钟");
            }
            case EXPIRED -> {
                return reject(response, 40302, "PASSWORD_EXPIRED", "密码过期，需更换");
            }
            default -> {
                log.error("Unexpected password verify status: {}", result.getStatus());
                return reject(response, 40302, "PASSWORD_INVALID", "密码验证失败");
            }
        }
    }

    /**
     * 判断请求路径是否需要支付密码验证。
     */
    private boolean isProtectedPath(String path) {
        return PROTECTED_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * 从请求属性解析商户 ID。
     */
    private Long resolveMerchantId(HttpServletRequest request) {
        Object attr = request.getAttribute(MERCHANT_ID_ATTR);
        if (attr instanceof Number number) {
            return number.longValue();
        }
        if (attr instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 从请求属性解析租户 ID。
     */
    private String resolveTenantId(HttpServletRequest request) {
        Object attr = request.getAttribute(TENANT_ID_ATTR);
        if (attr instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return null;
    }

    /**
     * 返回错误响应。
     */
    private boolean reject(HttpServletResponse response, int httpStatus, String code, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                String.format("{\"code\":%d,\"message\":\"%s\",\"data\":null}", code, message));
        return false;
    }
}