package org.nexus.gateway.orchestration.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link WebhookSignatureService} 单元测试（P4-T5）。
 *
 * <p>验证 HMAC-SHA256 签名计算与验证：
 * <ul>
 *   <li>签名确定性：相同 payload + 相同 secret 产生相同签名</li>
 *   <li>签名对称性：sorted-key JSON 保证发送方与接收方一致</li>
 *   <li>验证正确性：verify 对正确签名返回 true，错误签名返回 false</li>
 *   <li>常量时间比较：防止时序侧信道</li>
 *   <li>空 secret 处理</li>
 * </ul>
 */
class WebhookSignatureServiceTest {

    private final WebhookSignatureService service = new WebhookSignatureService();
    private static final String SECRET = "test_webhook_secret_12345";

    private Map<String, Object> samplePayload() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", "payment.succeeded");
        m.put("payment_id", "pay_001");
        m.put("amount", 10000L);
        m.put("currency", "NEX");
        return m;
    }

    @Test
    @DisplayName("sign: 相同 payload + 相同 secret 产生相同签名（确定性）")
    void sign_deterministic() {
        Map<String, Object> payload = samplePayload();
        String sig1 = service.sign(payload, SECRET);
        String sig2 = service.sign(payload, SECRET);
        assertEquals(sig1, sig2, "Same payload+secret should produce same signature");
        assertEquals(64, sig1.length(), "HMAC-SHA256 hex should be 64 chars");
    }

    @Test
    @DisplayName("sign: 不同 key 顺序产生相同签名（sorted-key canonicalization）")
    void sign_keyOrderIndependent() {
        Map<String, Object> payload1 = new LinkedHashMap<>();
        payload1.put("a", "1");
        payload1.put("b", "2");
        payload1.put("c", "3");

        Map<String, Object> payload2 = new LinkedHashMap<>();
        payload2.put("c", "3");
        payload2.put("a", "1");
        payload2.put("b", "2");

        String sig1 = service.sign(payload1, SECRET);
        String sig2 = service.sign(payload2, SECRET);
        assertEquals(sig1, sig2, "Different key order should produce same signature (canonicalization)");
    }

    @Test
    @DisplayName("sign: 不同 payload 产生不同签名")
    void sign_differentPayloadDifferentSignature() {
        Map<String, Object> p1 = samplePayload();
        Map<String, Object> p2 = samplePayload();
        p2.put("payment_id", "pay_002");
        assertNotEquals(service.sign(p1, SECRET), service.sign(p2, SECRET));
    }

    @Test
    @DisplayName("sign: 不同 secret 产生不同签名")
    void sign_differentSecretDifferentSignature() {
        Map<String, Object> payload = samplePayload();
        assertNotEquals(service.sign(payload, SECRET), service.sign(payload, "other_secret"));
    }

    @Test
    @DisplayName("sign: 空 secret 返回空字符串")
    void sign_emptySecretReturnsEmpty() {
        Map<String, Object> payload = samplePayload();
        assertEquals("", service.sign(payload, ""));
        assertEquals("", service.sign(payload, null));
    }

    @Test
    @DisplayName("sign: null payload 抛 IllegalArgumentException")
    void sign_nullPayloadThrows() {
        assertThrows(IllegalArgumentException.class, () -> service.sign(null, SECRET));
    }

    @Test
    @DisplayName("verify: 正确签名返回 true")
    void verify_correctSignatureReturnsTrue() {
        Map<String, Object> payload = samplePayload();
        String signature = service.sign(payload, SECRET);
        assertTrue(service.verify(payload, SECRET, signature));
    }

    @Test
    @DisplayName("verify: 错误签名返回 false")
    void verify_wrongSignatureReturnsFalse() {
        Map<String, Object> payload = samplePayload();
        assertFalse(service.verify(payload, SECRET, "deadbeef" + "0".repeat(56)));
    }

    @Test
    @DisplayName("verify: 空签名返回 false")
    void verify_emptySignatureReturnsFalse() {
        Map<String, Object> payload = samplePayload();
        assertFalse(service.verify(payload, SECRET, ""));
        assertFalse(service.verify(payload, SECRET, null));
    }

    @Test
    @DisplayName("verify: 空 secret 返回 false")
    void verify_emptySecretReturnsFalse() {
        Map<String, Object> payload = samplePayload();
        String signature = service.sign(payload, SECRET);
        assertFalse(service.verify(payload, "", signature));
        assertFalse(service.verify(payload, null, signature));
    }

    @Test
    @DisplayName("signRaw + verifyRaw: 原始 JSON 字符串签名对称")
    void signRaw_verifyRaw_symmetric() {
        String json = "{\"amount\":10000,\"currency\":\"NEX\",\"event\":\"payment.succeeded\",\"payment_id\":\"pay_001\"}";
        String sig = service.signRaw(json, SECRET);
        assertTrue(service.verifyRaw(json, SECRET, sig));
        assertFalse(service.verifyRaw(json, SECRET, "0".repeat(64)));
    }

    @Test
    @DisplayName("constantTimeEquals: 相同字符串返回 true")
    void constantTimeEquals_equalStrings() {
        assertTrue(WebhookSignatureService.constantTimeEquals("abc", "abc"));
        assertTrue(WebhookSignatureService.constantTimeEquals("", ""));
    }

    @Test
    @DisplayName("constantTimeEquals: 不同字符串返回 false")
    void constantTimeEquals_differentStrings() {
        assertFalse(WebhookSignatureService.constantTimeEquals("abc", "abd"));
        assertFalse(WebhookSignatureService.constantTimeEquals("abc", "ab"));
        assertFalse(WebhookSignatureService.constantTimeEquals("abc", "abcd"));
        assertFalse(WebhookSignatureService.constantTimeEquals(null, "abc"));
        assertFalse(WebhookSignatureService.constantTimeEquals("abc", null));
    }

    @Test
    @DisplayName("sign: 签名为 hex 小写")
    void sign_hexLowercase() {
        Map<String, Object> payload = samplePayload();
        String sig = service.sign(payload, SECRET);
        assertEquals(sig.toLowerCase(), sig, "Signature should be lowercase hex");
        assertTrue(sig.matches("[0-9a-f]{64}"), "Signature should be 64 hex chars");
    }

    // ===== v2 签名（短期项 #4c：绑定 deliveryId + timestamp，防重放）=====

    @Test
    @DisplayName("signV2: 前缀 v2: 且确定性")
    void signV2_deterministic() {
        Map<String, Object> payload = samplePayload();
        String s1 = service.signV2(payload, SECRET, "dlv_001", "2026-09-17T12:00:00Z");
        String s2 = service.signV2(payload, SECRET, "dlv_001", "2026-09-17T12:00:00Z");
        assertTrue(s1.startsWith("v2:"), "v2 签名应带 v2: 前缀");
        assertEquals(s1, s2, "相同输入产生相同 v2 签名");
        assertEquals(3 + 64, s1.length(), "v2: + 64 hex");
    }

    @Test
    @DisplayName("signV2: 绑定 deliveryId 与 timestamp（任一变化签名不同）")
    void signV2_bindsDeliveryIdAndTimestamp() {
        Map<String, Object> payload = samplePayload();
        String base = service.signV2(payload, SECRET, "dlv_001", "2026-09-17T12:00:00Z");
        assertNotEquals(base, service.signV2(payload, SECRET, "dlv_002", "2026-09-17T12:00:00Z"),
                "deliveryId 变化必须改变签名（v1 无此性质，可无限重放）");
        assertNotEquals(base, service.signV2(payload, SECRET, "dlv_001", "2026-09-17T12:00:01Z"),
                "timestamp 变化必须改变签名");
        // 同 payload 不同 deliveryId → 接收方可按 deliveryId 幂等去重
        assertNotEquals(
                service.signV2(payload, SECRET, "dlv_a", "2026-09-17T12:00:00Z"),
                service.signV2(payload, SECRET, "dlv_b", "2026-09-17T12:00:00Z"));
    }

    @Test
    @DisplayName("verifyV2: 正确签名 true；换 deliveryId/timestamp 签名 false；v1 签名 false")
    void verifyV2_correctAndTampered() {
        Map<String, Object> payload = samplePayload();
        String sig = service.signV2(payload, SECRET, "dlv_001", "2026-09-17T12:00:00Z");
        assertTrue(service.verifyV2(payload, SECRET, sig, "dlv_001", "2026-09-17T12:00:00Z"));
        // 篡改 deliveryId / timestamp（重放场景）→ 拒绝
        assertFalse(service.verifyV2(payload, SECRET, sig, "dlv_001", "2026-09-17T12:00:01Z"),
                "时间戳被篡改的 v2 签名必须拒绝");
        assertFalse(service.verifyV2(payload, SECRET, sig, "dlv_evil", "2026-09-17T12:00:00Z"),
                "deliveryId 被篡改的 v2 签名必须拒绝");
        // v1 签名不能通过 v2 验证
        assertFalse(service.verifyV2(payload, SECRET, service.sign(payload, SECRET), "dlv_001", "2026-09-17T12:00:00Z"));
        // v2 签名不能通过 v1 验证（前缀不同）
        assertFalse(service.verify(payload, SECRET, sig));
    }

    @Test
    @DisplayName("signV2: 空 secret 返回空串；verifyV2 空/非法签名返回 false")
    void signV2_emptySecretAndInvalidSignature() {
        Map<String, Object> payload = samplePayload();
        assertEquals("", service.signV2(payload, "", "dlv_001", "t"));
        assertEquals("", service.signV2(payload, null, "dlv_001", "t"));
        assertFalse(service.verifyV2(payload, SECRET, null, "dlv_001", "t"));
        assertFalse(service.verifyV2(payload, SECRET, "", "dlv_001", "t"));
        assertFalse(service.verifyV2(payload, SECRET, "v2:deadbeef", "dlv_001", "t"));
        assertFalse(service.verifyV2(payload, "", service.signV2(payload, SECRET, "d", "t"), "d", "t"));
    }

    @Test
    @DisplayName("canonicalV2: 长度前缀确定性快照")
    void canonicalV2_snapshot() {
        String c = WebhookSignatureService.canonicalV2("dlv_001", "2026-09-17T12:00:00Z", "{\"a\":1}");
        assertEquals("NXCW|7:dlv_001|20:2026-09-17T12:00:00Z|7:{\"a\":1}|", c);
    }
}