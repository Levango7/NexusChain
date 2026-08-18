package org.nexus.gateway.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stripe Webhook 签名验证器 — 验证 {@code Stripe-Signature} 头并防御重放攻击。
 *
 * <h2>Stripe 签名格式</h2>
 * <p>Stripe 在每个 webhook 请求头中携带：
 * <pre>
 *   Stripe-Signature: t=1492774577,v1=5257a8698d4d6d3b6f4d3a4d3a4d3a4d3a4d3a4d3a4d3a4d3a4d3a4d3a4d3a4d3a4d
 * </pre>
 * 其中 {@code t} 是 Stripe 服务器签发时间戳（Unix 秒），{@code v1} 是
 * {@code HMAC-SHA256(secret, t + "." + payload)} 的十六进制表示。</p>
 *
 * <h2>验证流程</h2>
 * <ol>
 *   <li>解析 {@code Stripe-Signature} 头，提取 {@code t} 与所有 {@code v1} 值</li>
 *   <li>重放保护：{@code |now - t| > replayToleranceSeconds} 则拒绝</li>
 *   <li>计算 {@code HMAC-SHA256(secret, t + "." + payload)} 并与每个 v1 常时比较</li>
 *   <li>任一 v1 匹配则通过；全部不匹配则拒绝</li>
 * </ol>
 *
 * <h2>Fail-closed 原则</h2>
 * <p>未配置 secret、缺失签名头、解析失败、时间戳越界、签名不匹配 —— 一律返回
 * {@code valid=false}。WebhookController 据此返回 401，绝不放行未验签的支付通知。</p>
 *
 * @see <a href="https://stripe.com/docs/webhooks#verify-events-officially">Stripe Webhook 签名验证</a>
 */
@Component
public class StripeWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookVerifier.class);

    /** 默认重放窗口 5 分钟，与 Stripe 官方 SDK 推荐值一致。 */
    private static final long DEFAULT_REPLAY_TOLERANCE_SECONDS = 300L;

    @Value("${nexus.connectors.stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${nexus.webhook.replay-tolerance-seconds:300}")
    private long replayToleranceSeconds = DEFAULT_REPLAY_TOLERANCE_SECONDS;

    /**
     * 验证 Stripe webhook 签名。
     *
     * @param payload       原始请求体字节（必须是 raw bytes，不能反序列化后再序列化）
     * @param signatureHeader {@code Stripe-Signature} 头值，可为 {@code null}
     * @return 验证结果
     */
    public WebhookVerifyResult verify(byte[] payload, String signatureHeader) {
        // 1. secret 必须配置
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("Stripe webhook secret (nexus.connectors.stripe.webhook-secret) not configured; rejecting");
            return WebhookVerifyResult.fail("Webhook secret not configured");
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            log.warn("Missing Stripe-Signature header");
            return WebhookVerifyResult.fail("Missing signature header");
        }
        if (payload == null) {
            log.warn("Null payload");
            return WebhookVerifyResult.fail("Null payload");
        }

        // 2. 解析签名头：t=...,v1=...,v1=...
        long timestamp;
        List<String> v1Signatures;
        try {
            String[] parts = signatureHeader.split(",");
            Long t = null;
            List<String> v1s = new ArrayList<>();
            for (String part : parts) {
                String[] kv = part.split("=", 2);
                if (kv.length != 2) continue;
                if ("t".equals(kv[0])) {
                    t = Long.parseLong(kv[1].trim());
                } else if ("v1".equals(kv[0])) {
                    v1s.add(kv[1].trim());
                }
            }
            if (t == null || v1s.isEmpty()) {
                log.warn("Stripe-Signature missing t or v1: {}", signatureHeader);
                return WebhookVerifyResult.fail("Malformed signature header");
            }
            timestamp = t;
            v1Signatures = v1s;
        } catch (NumberFormatException e) {
            log.warn("Stripe-Signature timestamp parse error: {}", e.getMessage());
            return WebhookVerifyResult.fail("Malformed timestamp");
        }

        // 3. 重放攻击防护
        long now = Instant.now().getEpochSecond();
        long delta = Math.abs(now - timestamp);
        if (delta > replayToleranceSeconds) {
            log.warn("Stripe webhook timestamp out of tolerance: now={} t={} delta={} > {}", now, timestamp, delta, replayToleranceSeconds);
            return WebhookVerifyResult.fail("Timestamp out of tolerance (replay suspected)");
        }

        // 4. 计算 expected = HMAC-SHA256(secret, "t.payload")
        String signedPayload = timestamp + "." + new String(payload, StandardCharsets.UTF_8);
        String expected;
        try {
            expected = hmacSha256Hex(webhookSecret, signedPayload);
        } catch (Exception e) {
            log.error("HMAC computation failed: {}", e.getMessage());
            return WebhookVerifyResult.fail("HMAC computation error");
        }

        // 5. 常时比较：任一 v1 匹配即通过
        for (String v1 : v1Signatures) {
            if (constantTimeEquals(expected, v1)) {
                return WebhookVerifyResult.ok();
            }
        }
        log.warn("Stripe webhook signature mismatch (expected={} actual={})", expected, v1Signatures);
        return WebhookVerifyResult.fail("Signature mismatch");
    }

    /** 计算 HMAC-SHA256 并返回小写十六进制字符串。 */
    private static String hmacSha256Hex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 常时字符串比较，避免签名验证的时序侧信道。 */
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

    /** Test-only: 直接注入 secret + tolerance，绕过 @Value 注入。 */
    void setWebhookSecret(String secret) {
        this.webhookSecret = secret;
    }

    void setReplayToleranceSeconds(long seconds) {
        this.replayToleranceSeconds = seconds;
    }

    /**
     * Test-only: 用当前实例配置生成一个合法的 Stripe-Signature 头值。
     * 测试用此方法构造 expected 签名，再交给 {@link #verify} 验证。
     */
    String signForTest(long timestamp, byte[] payload) throws Exception {
        String signedPayload = timestamp + "." + new String(payload, StandardCharsets.UTF_8);
        return "t=" + timestamp + ",v1=" + hmacSha256Hex(webhookSecret, signedPayload);
    }
}