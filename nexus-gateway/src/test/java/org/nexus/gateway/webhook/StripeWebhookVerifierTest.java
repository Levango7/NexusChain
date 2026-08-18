package org.nexus.gateway.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link StripeWebhookVerifier} 单元测试 — 覆盖合法签名、缺失 secret、缺失签名头、
 * 解析失败、重放攻击（timestamp 越界）、签名不匹配等场景。
 */
@DisplayName("StripeWebhookVerifier 验签测试")
class StripeWebhookVerifierTest {

    private StripeWebhookVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new StripeWebhookVerifier();
        verifier.setWebhookSecret("whsec_test_123");
        verifier.setReplayToleranceSeconds(300);
    }

    @Test
    @DisplayName("合法签名 + timestamp 在窗口内 → valid")
    void validSignature() throws Exception {
        byte[] payload = "{\"type\":\"payment_intent.succeeded\"}".getBytes(StandardCharsets.UTF_8);
        long ts = Instant.now().getEpochSecond();
        String sig = verifier.signForTest(ts, payload);

        WebhookVerifyResult r = verifier.verify(payload, sig);
        assertTrue(r.isValid(), "should be valid: " + r.getReason());
    }

    @Test
    @DisplayName("secret 未配置 → fail")
    void secretNotConfigured() {
        verifier.setWebhookSecret("");
        WebhookVerifyResult r = verifier.verify("{}".getBytes(), "t=1,v1=abc");
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("secret"));
    }

    @Test
    @DisplayName("缺失 Stripe-Signature 头 → fail")
    void missingSignatureHeader() {
        WebhookVerifyResult r = verifier.verify("{}".getBytes(), null);
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("Missing"));
    }

    @Test
    @DisplayName("空签名头 → fail")
    void emptySignatureHeader() {
        WebhookVerifyResult r = verifier.verify("{}".getBytes(), "");
        assertFalse(r.isValid());
    }

    @Test
    @DisplayName("null payload → fail")
    void nullPayload() {
        WebhookVerifyResult r = verifier.verify(null, "t=1,v1=abc");
        assertFalse(r.isValid());
    }

    @Test
    @DisplayName("签名头缺 t → fail")
    void missingTimestamp() {
        WebhookVerifyResult r = verifier.verify("{}".getBytes(), "v1=abc");
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("Malformed"));
    }

    @Test
    @DisplayName("签名头缺 v1 → fail")
    void missingV1() {
        WebhookVerifyResult r = verifier.verify("{}".getBytes(), "t=12345");
        assertFalse(r.isValid());
    }

    @Test
    @DisplayName("timestamp 非数字 → fail")
    void malformedTimestamp() {
        WebhookVerifyResult r = verifier.verify("{}".getBytes(), "t=notanumber,v1=abc");
        assertFalse(r.isValid());
    }

    @Test
    @DisplayName("timestamp 超过 5 分钟窗口 → fail（重放攻击防护）")
    void replayAttack() throws Exception {
        byte[] payload = "{\"type\":\"payment_intent.succeeded\"}".getBytes(StandardCharsets.UTF_8);
        long oldTs = Instant.now().getEpochSecond() - 600; // 10 分钟前
        String sig = verifier.signForTest(oldTs, payload);

        WebhookVerifyResult r = verifier.verify(payload, sig);
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("tolerance") || r.getReason().contains("replay"));
    }

    @Test
    @DisplayName("timestamp 未来超过窗口 → fail")
    void futureTimestamp() throws Exception {
        byte[] payload = "{\"type\":\"payment_intent.succeeded\"}".getBytes(StandardCharsets.UTF_8);
        long futureTs = Instant.now().getEpochSecond() + 600;
        String sig = verifier.signForTest(futureTs, payload);

        WebhookVerifyResult r = verifier.verify(payload, sig);
        assertFalse(r.isValid());
    }

    @Test
    @DisplayName("签名不匹配 → fail")
    void signatureMismatch() {
        byte[] payload = "{\"type\":\"payment_intent.succeeded\"}".getBytes(StandardCharsets.UTF_8);
        long ts = Instant.now().getEpochSecond();
        String sig = "t=" + ts + ",v1=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

        WebhookVerifyResult r = verifier.verify(payload, sig);
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("mismatch"));
    }

    @Test
    @DisplayName("多 v1 候选：任一匹配即通过（Stripe 轮换密钥兼容）")
    void multipleV1Candidates() throws Exception {
        byte[] payload = "{\"type\":\"payment_intent.succeeded\"}".getBytes(StandardCharsets.UTF_8);
        long ts = Instant.now().getEpochSecond();
        String validSig = verifier.signForTest(ts, payload);
        // 在合法 v1 前插入一个非法 v1
        String sig = "t=" + ts + ",v1=invalid,v1=" + extractV1(validSig);

        WebhookVerifyResult r = verifier.verify(payload, sig);
        assertTrue(r.isValid(), "should be valid: " + r.getReason());
    }

    private static String extractV1(String sig) {
        for (String part : sig.split(",")) {
            String[] kv = part.split("=", 2);
            if ("v1".equals(kv[0])) return kv[1];
        }
        return "";
    }
}