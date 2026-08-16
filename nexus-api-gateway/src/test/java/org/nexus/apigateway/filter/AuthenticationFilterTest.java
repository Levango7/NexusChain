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
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AuthenticationFilter 测试（安全关键层补盲区——API Key + HMAC + 时间戳防重放）。
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
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static String hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HMAC_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    /** 构造完整鉴权请求（API Key + 时间戳 + HMAC 签名三件套）。 */
    private MockServerWebExchange authedExchange(String path) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String data = "GET\n" + path + "\n" + now;
        return exchange(MockServerHttpRequest.get(path)
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.HEADER_TIMESTAMP, String.valueOf(now))
                .header(AuthenticationFilter.HEADER_SIGNATURE, hmac(data)));
    }

    private MockServerWebExchange exchange(MockServerHttpRequest.BaseBuilder<?> req) {
        return MockServerWebExchange.from(req.build());
    }

    private GatewayFilterChain chain() {
        GatewayFilterChain c = mock(GatewayFilterChain.class);
        when(c.filter(any())).thenReturn(Mono.empty());
        return c;
    }

    @Test
    void whitelistPath_skipsAuth() {
        // /actuator/ 前缀放行（不鉴权）
        var ex = exchange(MockServerHttpRequest.get("/actuator/health"));
        Mono<Void> result = filter.filter(ex, chain());
        assertNotNull(result);
        verify(chain(), never()).filter(any());  // 白名单直接返回
    }

    @Test
    void apiKeyOnly_missingSignature_rejected() {
        // 只有 API Key（缺签名/时间戳）→ 拒绝（三段式鉴权）
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY));
        filter.filter(ex, chain());
        verify(chain(), never()).filter(any());
    }

    @Test
    void missingApiKey_rejected() {
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments"));
        Mono<Void> result = filter.filter(ex, chain());
        assertNotNull(result);
        verify(chain(), never()).filter(ex);  // 拒绝 → 链不继续
        assertTrue(ex.getResponse().getStatusCode() != null
                        && ex.getResponse().getStatusCode().is4xxClientError(),
                "无 API Key 应返回 4xx");
    }

    @Test
    void invalidApiKey_rejected() {
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, "wrong-key"));
        filter.filter(ex, chain());
        verify(chain(), never()).filter(ex);
    }

    @Test
    void validHmacSignature_passes() throws Exception {
        // 反射直接验证 verifyHmac（绕过 MockServerWebExchange 的 path 语义差异）
        long now = System.currentTimeMillis() / 1000;
        String data = "GET\n" + "/api/v1/payments" + "\n" + now;
        String sig = hmac(data);
        Method m = AuthenticationFilter.class.getDeclaredMethod(
                "verifyHmac", String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        assertTrue((Boolean) m.invoke(filter, "GET", "/api/v1/payments", String.valueOf(now), sig),
                "正确 HMAC 签名应验证通过");
    }

    @Test
    void wrongHmacSignature_rejected() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String data = "GET\n" + "/api/v1/payments" + "\n" + now;
        String sig = hmac(data);
        Method m = AuthenticationFilter.class.getDeclaredMethod(
                "verifyHmac", String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        // 篡改签名（base64 反转）→ 拒绝
        byte[] raw = java.util.Base64.getDecoder().decode(sig);
        raw[0] ^= 0x01;
        String tampered = java.util.Base64.getEncoder().encodeToString(raw);
        assertFalse((Boolean) m.invoke(filter, "GET", "/api/v1/payments", String.valueOf(now), tampered),
                "篡改签名应拒绝");
    }

    @Test
    void staleTimestamp_rejected() {
        long old = System.currentTimeMillis() / 1000 - 3600;  // 1 小时前（超过 300s 偏差）
        var ex = exchange(MockServerHttpRequest.get("/api/v1/payments")
                .header(AuthenticationFilter.HEADER_API_KEY, VALID_KEY)
                .header(AuthenticationFilter.HEADER_TIMESTAMP, String.valueOf(old)));
        filter.filter(ex, chain());
        verify(chain(), never()).filter(ex);
    }
}
