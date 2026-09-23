package org.nexus.gateway.logging;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 日志上下文拦截器（任务 #28）。
 *
 * <p>在请求处理前从请求头/属性中提取业务上下文字段（merchantId, orderId 等），
 * 设置到 MDC 中，使请求处理链中的所有日志自动携带业务标识。
 * 请求结束后清理 MDC，避免线程池复用导致的上下文泄漏。</p>
 *
 * <p>提取顺序：请求属性 > 请求头 > 请求参数。</p>
 *
 * <p>注册方式：在 {@code WebConfig.addInterceptors} 中添加，
 * 应在其他业务拦截器之后执行（以便读取已设置的请求属性）。</p>
 */
@Component
public class LogContextInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LogContextInterceptor.class);

    /** 请求头/属性名常量 */
    private static final String HEADER_MERCHANT_ID = "X-NexusChain-MerchantId";
    private static final String HEADER_ORDER_ID = "X-NexusChain-OrderId";
    private static final String HEADER_PAYMENT_ID = "X-NexusChain-PaymentId";
    private static final String HEADER_CONNECTOR_TYPE = "X-NexusChain-ConnectorType";

    /** 请求属性名常量（由其他拦截器设置） */
    private static final String ATTR_MERCHANT_ID = "nexus.merchantId";

    private final BusinessLogContext businessLogContext;

    public LogContextInterceptor(BusinessLogContext businessLogContext) {
        this.businessLogContext = businessLogContext;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 优先从请求属性获取（由 ApiKeyInterceptor 等上游拦截器设置）
        String merchantId = getFromAttributeOrHeader(request, ATTR_MERCHANT_ID, HEADER_MERCHANT_ID);
        String orderId = getFromHeaderOrParam(request, HEADER_ORDER_ID, "orderId");
        String paymentId = getFromHeaderOrParam(request, HEADER_PAYMENT_ID, "paymentId");
        String connectorType = getFromHeaderOrParam(request, HEADER_CONNECTOR_TYPE, "connectorType");

        businessLogContext.setContext(merchantId, orderId, paymentId, connectorType);

        if (log.isDebugEnabled()) {
            log.debug("Log context set: merchantId={}, orderId={}, paymentId={}, connectorType={}",
                    merchantId, orderId, paymentId, connectorType);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                 Object handler, Exception ex) {
        businessLogContext.clearContext();
    }

    /**
     * 优先从请求属性获取，其次从请求头获取。
     */
    private String getFromAttributeOrHeader(HttpServletRequest request, String attrName, String headerName) {
        Object attrValue = request.getAttribute(attrName);
        if (attrValue != null) {
            return attrValue.toString();
        }
        return request.getHeader(headerName);
    }

    /**
     * 优先从请求头获取，其次从请求参数获取。
     */
    private String getFromHeaderOrParam(HttpServletRequest request, String headerName, String paramName) {
        String headerValue = request.getHeader(headerName);
        if (headerValue != null && !headerValue.isEmpty()) {
            return headerValue;
        }
        return request.getParameter(paramName);
    }
}