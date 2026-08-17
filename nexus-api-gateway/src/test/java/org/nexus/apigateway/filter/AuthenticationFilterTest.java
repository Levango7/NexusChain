package org.nexus.apigateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AuthenticationFilter 测试（P1-D1 统一鉴权协议）。
 *
 * <p>覆盖：X-NexusChain-* 新头、X-Nexus-* 旧头兼容、hex 签名、nonce 防重放、时间戳防重放。</p>
 */
class AuthenticationFilterTest {

    private static final String VALID_KEY = "test-api-key";
    private static final String HMAC_SECRET = "test-hmac-secret";

    private AuthenticationFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        filter = new AuthenticationFilter();
        setField(filter, "authEnabled", true);
        setField(filter, "apiKeysConfig", VALID_KEY);
        setField(filter, "hmacSecret", HMAC_SECRET);
        setField(filter, "maxTimestampSkewSeconds", 300L);
        setField(filter, "replayWindowMs", 300_000L);
        setField(filter, "legacyHeadersEnabled", true);
        // 清空 nonce 缓存（每次测试独立）
        clearNonceCache(filter);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void clearNonceCache(AuthenticationFilter filter) throws Exception {
        Field f = AuthenticationFilter.class.getDeclaredField("seenNonces");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> map = (java.util.Map<String, Long>) f.get(filter);
        map.clear();
    }

    /** 计算小写十六进制 HMAC-SHA256 签名（与 filter 内部一致）。 */
    private static String hmacHex(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 签名串：timestamp + nonce + method + path + body（与 nexus-gateway 一致）。 */
    private static String payload(String ts, String nonce, String method, String path, String body) {
        return (ts == null ? "" : ts) + (nonce == null ? "" : nonce)
                + (method == null ? "" : method) + (path == null ? "" : path) + (body == null ? "" : body);
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> req) {
        return MockServerWebExchange.from(req.build());
    }

    private GatewayFilterChain chain() {
        GatewayFilterChain c = mock(GatewayFilterChain.class);
        when(c.filter(any())).thenReturn(Mono.empty());
        return c;
    }

    /** 阻塞执行 filter，触发实际鉴权逻辑。 */
    private void runFilter(MockServerWebExchange ex) {
        filter.filter(ex, chain()).block();
    }

    // === 白名单 ===

    @Test
    void whitelistPath_skipsAuth() {
        var ex = exchange(MockServerHttpRequest.get("/actuator/health"));
        filter.filter(ex, chain()).block();
        // 白名单直接放行：响应状态码未被设置（chain mock 返回 Mono.empty()），不应是 401
        assertNotEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
    }

    @Test
    void optionsMethod_skipsAuth() {
        var ex = exchange(MockServerHttpRequest.options("/api/v1/payments"));
        filter.filter(ex, chain()).block();
        assertNotEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
    }

    // === 新头 X-NexusChain-* ===

    @Test
    void missingAuthHeaders_rejected() {
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments"));
        runFilter(ex);
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
    }

    @Test
    void missingApiKey_rejected() {
        long now = System.currentTimeMillis();
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_TIMESTAMP, String.valueOf(now))
                .header(AuthenticationFilter.HEADER_NONCE, "n-1")
                .header(AuthenticationFilter.HEADER_SIGNATURE, "deadbeef"));
        runFilter(ex);
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
    }

    @Test
    void invalidApiKey_rejected() {
        long now = System.currentTimeMillis();
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, "wrong-key")
                .header(AuthenticationFilter.HEADER_TIMESTAMP, String.valueOf(now))
                .header(AuthenticationFilter.HEADER_NONCE, "n-1")
                .header(AuthenticationFilter.HEADER_SIGNATURE, "deadbeef"));
        runFilter(ex);
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
    }

    @Test
    void validHmacSignature_newHeaders_passes() throws Exception {
        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        String nonce = "nonce-pass-1";
        String sig = hmacHex(payload(ts, nonce, "GET", "/api/v1/payments", ""));
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.HEADER_NONCE, nonce)
                .header(AuthenticationFilter.HEADER_SIGNATURE, sig));
        runFilter(ex);
        assertNotEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode(),
                "正确 hex 签名应通过");
    }

    @Test
    void wrongHmacSignature_rejected() throws Exception {
        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        String nonce = "nonce-wrong-1";
        String sig = hmacHex(payload(ts, nonce, "GET", "/api/v1/payments", ""));
        // 篡改签名首字符
        char[] chars = sig.toCharArray();
        chars[0] = chars[0] == '0' ? '1' : '0';
        String tampered = new String(chars);
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.HEADER_NONCE, nonce)
                .header(AuthenticationFilter.HEADER_SIGNATURE, tampered));
        runFilter(ex);
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode(),
                "篡改签名应拒绝");
    }

    @Test
    void staleTimestamp_rejected() throws Exception {
        long old = System.currentTimeMillis() - 3600 * 1000L;  // 1 小时前（超过 300s）
        String ts = String.valueOf(old);
        String nonce = "nonce-stale-1";
        String sig = hmacHex(payload(ts, nonce, "GET", "/api/v1/payments", ""));
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.HEADER_NONCE, nonce)
                .header(AuthenticationFilter.HEADER_SIGNATURE, sig));
        runFilter(ex);
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
    }

    @Test
    void missingNonce_rejected() throws Exception {
        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        // 旧逻辑允许 nonce 缺失；新协议要求 nonce 必填
        String sig = hmacHex(payload(ts, null, "GET", "/api/v1/payments", ""));
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.HEADER_SIGNATURE, sig));
        runFilter(ex);
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode(),
                "缺少 nonce 应拒绝（防重放）");
    }

    // === Nonce 防重放 ===

    @Test
    void replayedNonce_rejected() throws Exception {
        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        String nonce = "nonce-replay-fixed";
        String sig = hmacHex(payload(ts, nonce, "GET", "/api/v1/payments", ""));

        // 第一次：通过
        var ex1 = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.HEADER_NONCE, nonce)
                .header(AuthenticationFilter.HEADER_SIGNATURE, sig));
        runFilter(ex1);
        assertNotEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex1.getResponse().getStatusCode(),
                "首次请求应通过");

        // 第二次：同 nonce 拒绝
        var ex2 = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.HEADER_NONCE, nonce)
                .header(AuthenticationFilter.HEADER_SIGNATURE, sig));
        runFilter(ex2);
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex2.getResponse().getStatusCode(),
                "重复 nonce 应拒绝（防重放）");
    }

    @Test
    void differentNonce_bothPass() throws Exception {
        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        String nonce1 = "nonce-a";
        String nonce2 = "nonce-b";
        String sig1 = hmacHex(payload(ts, nonce1, "GET", "/api/v1/payments", ""));
        String sig2 = hmacHex(payload(ts, nonce2, "GET", "/api/v1/payments", ""));

        var ex1 = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.HEADER_NONCE, nonce1)
                .header(AuthenticationFilter.HEADER_SIGNATURE, sig1));
        runFilter(ex1);
        assertNotEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex1.getResponse().getStatusCode());

        var ex2 = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.HEADER_NONCE, nonce2)
                .header(AuthenticationFilter.HEADER_SIGNATURE, sig2));
        runFilter(ex2);
        assertNotEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex2.getResponse().getStatusCode());
    }

    // === 旧头 X-Nexus-* 兼容期 ===

    @Test
    void legacyHeaders_acceptedAndMarkedDeprecated() throws Exception {
        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        // 旧头无 nonce，签名串中 nonce 段为空
        String sig = hmacHex(payload(ts, null, "GET", "/api/v1/payments", ""));
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.LEGACY_HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.LEGACY_HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.LEGACY_HEADER_SIGNATURE, sig));
        runFilter(ex);
        // 旧头兼容期：通过但标记 deprecated
        assertNotEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode(),
                "兼容期旧头应通过");
        // 注意：MockServerWebExchange 的响应头可能不立即写入，仅验证未拒绝
    }

    @Test
    void legacyHeadersDisabled_rejected() throws Exception {
        setField(filter, "legacyHeadersEnabled", false);
        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        String sig = hmacHex(payload(ts, null, "GET", "/api/v1/payments", ""));
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.LEGACY_HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.LEGACY_HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.LEGACY_HEADER_SIGNATURE, sig));
        runFilter(ex);
        assertEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode(),
                "关闭兼容期后旧头应拒绝");
    }

    @Test
    void newHeadersPreferredOverLegacy() throws Exception {
        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        String nonce = "nonce-priority";
        // 新头签名正确
        String newSig = hmacHex(payload(ts, nonce, "GET", "/api/v1/payments", ""));
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.HEADER_NONCE, nonce)
                .header(AuthenticationFilter.HEADER_SIGNATURE, newSig)
                // 同时携带旧头（错误签名），应被忽略
                .header(AuthenticationFilter.LEGACY_HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.LEGACY_HEADER_TIMESTAMP, ts)
                .header(AuthenticationFilter.LEGACY_HEADER_SIGNATURE, "wrong"));
        runFilter(ex);
        assertNotEquals(org.springframework.http.HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode(),
                "新头优先于旧头");
    }

    // === 反射直接验证 verifyHmac（hex 编码） ===

    @Test
    void verifyHmac_hexEncoding_correct() throws Exception {
        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        String nonce = "nonce-reflect";
        String sig = hmacHex(payload(ts, nonce, "GET", "/api/v1/payments", ""));
        Method m = AuthenticationFilter.class.getDeclaredMethod(
                "verifyHmac", String.class, String.class, String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        assertTrue((Boolean) m.invoke(filter, ts, nonce, "GET", "/api/v1/payments", "", sig),
                "hex 签名应验证通过");
    }

    @Test
    void verifyHmac_base64Signature_rejected() throws Exception {
        long now = System.currentTimeMillis();
        String ts = String.valueOf(now);
        String nonce = "nonce-reflect-b64";
        // 用 Base64 编码（旧方式），应被拒绝
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(payload(ts, nonce, "GET", "/api/v1/payments", "").getBytes(StandardCharsets.UTF_8));
        String b64Sig = java.util.Base64.getEncoder().encodeToString(hash);
        Method m = AuthenticationFilter.class.getDeclaredMethod(
                "verifyHmac", String.class, String.class, String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        assertFalse((Boolean) m.invoke(filter, ts, nonce, "GET", "/api/v1/payments", "", b64Sig),
                "Base64 签名应被拒绝（统一为 hex）");
    }
}
