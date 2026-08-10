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
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(req);
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
        ContentCachingRequestWrapper w1 = new ContentCachingRequestWrapper(req1);
        w1.getContentAsByteArray();
        assertTrue(interceptor.preHandle(w1, new MockHttpServletResponse(), null));

        // 第二次同 nonce：被拒
        MockHttpServletRequest req2 = new MockHttpServletRequest(method, path);
        req2.setContent(body.getBytes());
        req2.addHeader("X-NexusChain-Signature", sig);
        req2.addHeader("X-NexusChain-Timestamp", ts);
        req2.addHeader("X-NexusChain-Nonce", nonce);
        ContentCachingRequestWrapper w2 = new ContentCachingRequestWrapper(req2);
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
}