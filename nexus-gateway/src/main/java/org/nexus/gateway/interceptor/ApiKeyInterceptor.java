package org.nexus.gateway.interceptor;

import org.nexus.gateway.MerchantService;
import org.nexus.gateway.model.Merchant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import org.nexus.gateway.dto.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

/**
 * API Key authentication interceptor.
 * Validates the X-NexusChain-ApiKey header against registered merchant keys.
 *
 * Public paths (checkout, webhooks) are excluded from authentication.
 */
@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyInterceptor.class);
    private static final String API_KEY_HEADER = "X-NexusChain-ApiKey";

    private final MerchantService merchantService;

    public ApiKeyInterceptor(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String apiKey = request.getHeader(API_KEY_HEADER);

        if (apiKey == null || apiKey.isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(String.format("{\"code\":%d,\"message\":\"%s\",\"data\":null}", ErrorCode.MISSING_API_KEY.getCode(), ErrorCode.MISSING_API_KEY.getMessage()));
            return false;
        }

        Optional<Merchant> merchant = merchantService.findByApiKey(apiKey);
        if (!merchant.isPresent()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(String.format("{\"code\":%d,\"message\":\"%s\",\"data\":null}", ErrorCode.INVALID_API_KEY.getCode(), ErrorCode.INVALID_API_KEY.getMessage()));
            return false;
        }

        Merchant m = merchant.get();
        if (m.getVerificationStatus() != Merchant.VerificationStatus.VERIFIED) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(String.format("{\"code\":%d,\"message\":\"%s\",\"data\":null}", ErrorCode.MERCHANT_NOT_VERIFIED.getCode(), ErrorCode.MERCHANT_NOT_VERIFIED.getMessage()));
            return false;
        }

        // Attach merchant context to request for downstream use
        request.setAttribute("nexus.merchantId", m.getId());
        request.setAttribute("nexus.merchantCode", m.getMerchantCode());
        return true;
    }
}