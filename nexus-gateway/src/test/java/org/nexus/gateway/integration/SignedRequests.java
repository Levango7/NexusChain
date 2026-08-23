package org.nexus.gateway.integration;

import org.nexus.gateway.security.RequestSignatureInterceptor;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 集成测试用 HMAC 请求签名工具（P1-3 配套）。
 *
 * <p>{@code RequestSignatureInterceptor} 已覆盖 {@code /api/v1/orders|refunds|payments/**}，
 * 未携带签名的请求会被拒绝。本工具以 MockMvc {@link RequestPostProcessor} 形式为请求
 * 自动补齐 Timestamp / Nonce / Signature 三个头。</p>
 *
 * <p>密钥必须与对应 profile 的 {@code nexus.security.requestSigningSecret} 一致：
 * sandbox 见 {@code application-sandbox.yml}，test 见 {@code application-test.yml}。</p>
 */
public final class SignedRequests {

    /** 与 application-sandbox.yml 的 nexus.security.requestSigningSecret 一致（仅沙箱）。 */
    public static final String SANDBOX_SECRET = "sandbox-signing-secret-do-not-use-in-prod";
    /** 与 application-test.yml 的 nexus.security.requestSigningSecret 一致（仅测试）。 */
    public static final String TEST_SECRET = "test-signing-secret-do-not-use-in-prod";

    private SignedRequests() {
    }

    /** 为请求追加 sandbox profile 密钥的 HMAC 签名头。用法：{@code .with(sandbox())}。 */
    public static RequestPostProcessor sandbox() {
        return signed(SANDBOX_SECRET);
    }

    /** 为请求追加 test profile 密钥的 HMAC 签名头。用法：{@code .with(test())}。 */
    public static RequestPostProcessor test() {
        return signed(TEST_SECRET);
    }

    /**
     * 以给定密钥签名请求。规范串与拦截器一致：timestamp + nonce + method + path + body。
     */
    public static RequestPostProcessor signed(String secret) {
        return request -> {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String nonce = UUID.randomUUID().toString();
            byte[] content = request.getContentAsByteArray();
            String body = (content == null || content.length == 0)
                    ? "" : new String(content, StandardCharsets.UTF_8);
            String signature = RequestSignatureInterceptor.computeSignature(
                    timestamp, nonce, request.getMethod(), request.getRequestURI(), body, secret);
            request.addHeader("X-NexusChain-Timestamp", timestamp);
            request.addHeader("X-NexusChain-Nonce", nonce);
            request.addHeader("X-NexusChain-Signature", signature);
            return request;
        };
    }
}
