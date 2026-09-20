package org.nexus.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RequestSignatureInterceptor} 单元测试：覆盖签名缺失、时间戳过期、
 * nonce 重放、签名不匹配、签名验证成功等分支。
 */
class RequestSignatureInterceptorTest {

    private static final String SECRET = "test-secret";

    private RequestSignatureInterceptor interceptor;
    private long now;

    @BeforeEach
    void setUp() {
        interceptor = new RequestSignatureInterceptor(SECRET);
        now = System.currentTimeMillis();
    }

    @Test
    @DisplayName("preHandle: 缺少签名头返回 401")
    void missingSignature_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/payments");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, null));
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("preHandle: 缺少时间戳头返回 401")
    void missingTimestamp_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/payments");
        req.addHeader("X-NexusChain-Signature", "sig");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, null));
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("preHandle: 非法时间戳返回 401")
    void invalidTimestamp_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/payments");
        req.addHeader("X-NexusChain-Signature", "sig");
        req.addHeader("X-NexusChain-Timestamp", "not-a-number");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, null));
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("preHandle: 过期时间戳返回 401")
    void expiredTimestamp_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/payments");
        req.addHeader("X-NexusChain-Signature", "sig");
        req.addHeader("X-NexusChain-Timestamp", String.valueOf(now - 10 * 60_000)); // 10 分钟前
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, null));
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("preHandle: 缺少 nonce 返回 401")
    void missingNonce_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/payments");
        req.addHeader("X-NexusChain-Signature", "sig");
        req.addHeader("X-NexusChain-Timestamp", String.valueOf(now));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, null));
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("preHandle: 签名不匹配返回 401")
    void signatureMismatch_rejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/payments");
        req.addHeader("X-NexusChain-Signature", "wrong-sig");
        req.addHeader("X-NexusChain-Timestamp", String.valueOf(now));
        req.addHeader("X-NexusChain-Nonce", "nonce-1");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(req, resp, null));
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("preHandle: 合法签名通过")
    void validSignature_passes() throws Exception {
        String ts = String.valueOf(now);
        String nonce = "nonce-ok";
        String method = "POST";
        String path = "/api/v1/payments";
        String body = "{\"amount\":100}";
        String sig = RequestSignatureInterceptor.computeSignature(ts, nonce, method, path, body, SECRET);

        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setContent(body.getBytes());
        req.addHeader("X-NexusChain-Signature", sig);
        req.addHeader("X-NexusChain-Timestamp", ts);
        req.addHeader("X-NexusChain-Nonce", nonce);

        // 使用 ContentCachingRequestWrapper 走 readBody 分支
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(req, 1024);
        // 触发 body 缓存
        wrapped.getContentAsByteArray();

        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(wrapped, resp, null));
    }

    @Test
    @DisplayName("preHandle: 相同 nonce 二次请求被拒（重放保护）")
    void replayedNonce_rejected() throws Exception {
        String ts = String.valueOf(now);
        String nonce = "nonce-replay";
        String method = "POST";
        String path = "/api/v1/payments";
        String body = "{}";
        String sig = RequestSignatureInterceptor.computeSignature(ts, nonce, method, path, body, SECRET);

        // 第一次：通过
        MockHttpServletRequest req1 = new MockHttpServletRequest(method, path);
        req1.setContent(body.getBytes());
        req1.addHeader("X-NexusChain-Signature", sig);
        req1.addHeader("X-NexusChain-Timestamp", ts);
        req1.addHeader("X-NexusChain-Nonce", nonce);
        ContentCachingRequestWrapper w1 = new ContentCachingRequestWrapper(req1, 1024);
        w1.getContentAsByteArray();
        assertTrue(interceptor.preHandle(w1, new MockHttpServletResponse(), null));

        // 第二次同 nonce：被拒
        MockHttpServletRequest req2 = new MockHttpServletRequest(method, path);
        req2.setContent(body.getBytes());
        req2.addHeader("X-NexusChain-Signature", sig);
        req2.addHeader("X-NexusChain-Timestamp", ts);
        req2.addHeader("X-NexusChain-Nonce", nonce);
        ContentCachingRequestWrapper w2 = new ContentCachingRequestWrapper(req2, 1024);
        w2.getContentAsByteArray();
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(w2, resp2, null));
        assertEquals(401, resp2.getStatus());
    }

    @Test
    @DisplayName("preHandle: signingSecret 未配置时拒绝")
    void unconfiguredSecret_rejected() throws Exception {
        RequestSignatureInterceptor noSecret = new RequestSignatureInterceptor("");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/payments");
        req.addHeader("X-NexusChain-Signature", "sig");
        req.addHeader("X-NexusChain-Timestamp", String.valueOf(now));
        req.addHeader("X-NexusChain-Nonce", "nonce-x");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertFalse(noSecret.preHandle(req, resp, null));
        assertEquals(401, resp.getStatus());
    }

    @Test
    @DisplayName("computeSignature: 同输入产生同输出（确定性）")
    void computeSignature_deterministic() {
        String s1 = RequestSignatureInterceptor.computeSignature("1", "n", "POST", "/p", "b", "k");
        String s2 = RequestSignatureInterceptor.computeSignature("1", "n", "POST", "/p", "b", "k");
        assertEquals(s1, s2);
        // 64 位 hex
        assertEquals(64, s1.length());
    }

    // ===== v2 canonical（短期项 #4：长度前缀 + 抗字段边界碰撞）=====

    @Test
    @DisplayName("v2: canonical 长度前缀消除字段边界碰撞")
    void canonicalV2_noBoundaryCollision() {
        // v1 碰撞对：ts/nonce 字段边界平移产生同一拼接串
        String c1 = RequestSignatureInterceptor.canonicalV2("12", "34", "POST", "/p", "b");
        String c2 = RequestSignatureInterceptor.canonicalV2("123", "4", "POST", "/p", "b");
        assertNotEquals(c1, c2, "v2 长度前缀必须区分字段边界");
        // v1 下这两组确实碰撞（回归证据）
        assertEquals(
                RequestSignatureInterceptor.computeSignature("12", "34", "POST", "/p", "b", SECRET),
                RequestSignatureInterceptor.computeSignature("123", "4", "POST", "/p", "b", SECRET),
                "v1 无分隔符拼接确实碰撞——这正是引入 v2 的原因");
        assertNotEquals(
                RequestSignatureInterceptor.computeSignatureV2("12", "34", "POST", "/p", "b", SECRET),
                RequestSignatureInterceptor.computeSignatureV2("123", "4", "POST", "/p", "b", SECRET));
    }

    @Test
    @DisplayName("v2: 签名头带 v2: 前缀并被服务端接受")
    void v2Signature_accepted() throws Exception {
        String ts = String.valueOf(now);
        String nonce = "nonce-v2";
        String sig = RequestSignatureInterceptor.computeSignatureV2(ts, nonce, "POST", "/api/v1/payments", "{}", SECRET);
        assertTrue(sig.startsWith("v2:"));
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/payments");
        req.setContent("{}".getBytes());
        req.addHeader("X-NexusChain-Signature", sig);
        req.addHeader("X-NexusChain-Timestamp", ts);
        req.addHeader("X-NexusChain-Nonce", nonce);
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(req, 1024);
        wrapped.getContentAsByteArray();
        assertTrue(interceptor.preHandle(wrapped, new MockHttpServletResponse(), null));
    }

    @Test
    @DisplayName("v2: legacy-disabled 时 v1 签名被拒绝、v2 仍接受")
    void legacyDisabled_rejectsV1AcceptsV2() throws Exception {
        RequestSignatureInterceptor strict = new RequestSignatureInterceptor(SECRET, false);
        String ts = String.valueOf(now);
        String body = "{}";
        // v1 签名 → 拒绝
        MockHttpServletRequest r1 = new MockHttpServletRequest("POST", "/api/v1/payments");
        r1.setContent(body.getBytes());
        r1.addHeader("X-NexusChain-Signature",
                RequestSignatureInterceptor.computeSignature(ts, "nonce-legacy-1", "POST", "/api/v1/payments", body, SECRET));
        r1.addHeader("X-NexusChain-Timestamp", ts);
        r1.addHeader("X-NexusChain-Nonce", "nonce-legacy-1");
        ContentCachingRequestWrapper w1 = new ContentCachingRequestWrapper(r1, 1024);
        w1.getContentAsByteArray();
        assertFalse(strict.preHandle(w1, new MockHttpServletResponse(), null), "legacy-disabled 时 v1 必须拒绝");
        // v2 签名 → 接受
        MockHttpServletRequest r2 = new MockHttpServletRequest("POST", "/api/v1/payments");
        r2.setContent(body.getBytes());
        r2.addHeader("X-NexusChain-Signature",
                RequestSignatureInterceptor.computeSignatureV2(ts, "nonce-v2-2", "POST", "/api/v1/payments", body, SECRET));
        r2.addHeader("X-NexusChain-Timestamp", ts);
        r2.addHeader("X-NexusChain-Nonce", "nonce-v2-2");
        ContentCachingRequestWrapper w2 = new ContentCachingRequestWrapper(r2, 1024);
        w2.getContentAsByteArray();
        assertTrue(strict.preHandle(w2, new MockHttpServletResponse(), null), "legacy-disabled 时 v2 仍应接受");
    }
}