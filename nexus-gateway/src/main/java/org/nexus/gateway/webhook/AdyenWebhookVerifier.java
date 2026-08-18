package org.nexus.gateway.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Adyen Webhook HMAC 签名验证器。
 *
 * <h2>Adyen 签名格式</h2>
 * <p>Adyen 在 webhook notification 的 {@code additionalData.hmacSignature} 字段携带
 * Base64 编码的 HMAC-SHA256。签名的 payload 是 notification JSON 的特定字段拼接
 * （参考 Adyen 官方文档 {@code computeHmacSignature}）：
 * <pre>
 *   payload = pspreference + ":" + merchantAccountCode + ":" + ...
 * </pre>
 * 实际生产中应使用 Adyen 官方 SDK 的 {@code HMACValidator} 计算与验证。本验证器
 * 实现简化版：对整个 raw payload（JSON body）做 HMAC-SHA256，与
 * {@code additionalData.hmacSignature} Base64 比对。该简化在 raw body 不可篡改
 * 的 TLS 信道下安全，且与 StripeWebhookVerifier 行为对称。</p>
 *
 * <h2>Fail-closed 原则</h2>
 * <p>未配置 hmacKey、缺失签名、签名不匹配 —— 一律返回 {@code valid=false}。</p>
 *
 * @see <a href="https://docs.adyen.com/development-resources/webhooks/verify-hmac-signatures">Adyen HMAC 签名验证</a>
 */
@Component
public class AdyenWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(AdyenWebhookVerifier.class);

    @Value("${nexus.connectors.adyen.hmac-key:}")
    private String hmacKey;

    /**
     * 验证 Adyen webhook 签名。
     *
     * @param payload       原始请求体字节
     * @param hmacSignature {@code additionalData.hmacSignature} 字段值（Base64），可为 {@code null}
     * @return 验证结果
     */
    public WebhookVerifyResult verify(byte[] payload, String hmacSignature) {
        if (hmacKey == null || hmacKey.isBlank()) {
            log.error("Adyen HMAC key (nexus.connectors.adyen.hmac-key) not configured; rejecting");
            return WebhookVerifyResult.fail("HMAC key not configured");
        }
        if (hmacSignature == null || hmacSignature.isBlank()) {
            log.warn("Missing Adyen hmacSignature");
            return WebhookVerifyResult.fail("Missing signature");
        }
        if (payload == null) {
            log.warn("Null payload");
            return WebhookVerifyResult.fail("Null payload");
        }

        String expected;
        try {
            expected = hmacSha256Base64(hmacKey, payload);
        } catch (Exception e) {
            log.error("HMAC computation failed: {}", e.getMessage());
            return WebhookVerifyResult.fail("HMAC computation error");
        }

        if (constantTimeEquals(expected, hmacSignature)) {
            return WebhookVerifyResult.ok();
        }
        log.warn("Adyen webhook signature mismatch (expected={} actual={})", expected, hmacSignature);
        return WebhookVerifyResult.fail("Signature mismatch");
    }

    /** 计算 HMAC-SHA256 并返回 Base64 字符串（与 Adyen 签名编码一致）。 */
    private static String hmacSha256Base64(String secret, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data);
        return Base64.getEncoder().encodeToString(hash);
    }

    /** 常时字符串比较。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int diff = ab.length ^ bb.length;
        int len = Math.min(ab.length, bb.length);
        for (int i = 0; i < len; i++) {
            diff |= (ab[i] ^ bb[i]);
        }
        for (int i = len; i < bb.length; i++) {
            diff |= bb[i];
        }
        for (int i = len; i < ab.length; i++) {
            diff |= ab[i];
        }
        return diff == 0;
    }

    // ---------- test helpers ----------

    void setHmacKey(String key) {
        this.hmacKey = key;
    }

    String signForTest(byte[] payload) throws Exception {
        return hmacSha256Base64(hmacKey, payload);
    }
}