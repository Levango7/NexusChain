package org.nexus.gateway.webhook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AdyenWebhookVerifier} 单元测试 — 覆盖合法签名、缺失 hmacKey、缺失签名、
 * 签名不匹配等场景。
 */
@DisplayName("AdyenWebhookVerifier 验签测试")
class AdyenWebhookVerifierTest {

    private AdyenWebhookVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new AdyenWebhookVerifier();
        verifier.setHmacKey("DFB1EB5485897565A3A9CF9FDF8E8E8E8E8E8E8E8E8E8E8E8E8E8E8E8E8E8E");
    }

    @Test
    @DisplayName("合法签名 → valid")
    void validSignature() throws Exception {
        byte[] payload = "{\"pspReference\":\"test_REF\",\"eventCode\":\"AUTHORISATION\"}"
                .getBytes(StandardCharsets.UTF_8);
        String sig = verifier.signForTest(payload);

        WebhookVerifyResult r = verifier.verify(payload, sig);
        assertTrue(r.isValid(), "should be valid: " + r.getReason());
    }

    @Test
    @DisplayName("hmacKey 未配置 → fail")
    void hmacKeyNotConfigured() {
        verifier.setHmacKey("");
        WebhookVerifyResult r = verifier.verify("{}".getBytes(), "abc");
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("HMAC key"));
    }

    @Test
    @DisplayName("缺失签名 → fail")
    void missingSignature() {
        WebhookVerifyResult r = verifier.verify("{}".getBytes(), null);
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("Missing"));
    }

    @Test
    @DisplayName("空签名 → fail")
    void emptySignature() {
        WebhookVerifyResult r = verifier.verify("{}".getBytes(), "");
        assertFalse(r.isValid());
    }

    @Test
    @DisplayName("null payload → fail")
    void nullPayload() {
        WebhookVerifyResult r = verifier.verify(null, "abc");
        assertFalse(r.isValid());
    }

    @Test
    @DisplayName("签名不匹配 → fail")
    void signatureMismatch() {
        byte[] payload = "{\"pspReference\":\"test_REF\"}".getBytes(StandardCharsets.UTF_8);
        WebhookVerifyResult r = verifier.verify(payload, "invalid-base64-signature");
        assertFalse(r.isValid());
        assertTrue(r.getReason().contains("mismatch"));
    }

    @Test
    @DisplayName("payload 篡改 → 签名不匹配 → fail")
    void payloadTampered() throws Exception {
        byte[] original = "{\"amount\":1000}".getBytes(StandardCharsets.UTF_8);
        String sig = verifier.signForTest(original);
        byte[] tampered = "{\"amount\":9999}".getBytes(StandardCharsets.UTF_8);

        WebhookVerifyResult r = verifier.verify(tampered, sig);
        assertFalse(r.isValid());
    }
}