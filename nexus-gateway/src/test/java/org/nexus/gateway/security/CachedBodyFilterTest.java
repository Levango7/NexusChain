package org.nexus.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link CachedBodyFilter} 单元测试：验证 shouldNotFilter 路径判定与 doFilterInternal 包装。
 */
class CachedBodyFilterTest {

    @Test
    @DisplayName("shouldNotFilter: 未覆盖签名路径（如 merchants）跳过")
    void shouldNotFilter_unsignedPath() {
        CachedBodyFilter filter = new CachedBodyFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/merchants/1");
        assertTrue(filter.shouldNotFilter(req));
    }

    @Test
    @DisplayName("shouldNotFilter: payments/refunds/orders 三个签名路径均不跳过")
    void shouldNotFilter_signedPathsWrapped() {
        CachedBodyFilter filter = new CachedBodyFilter();
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/payments")));
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/refunds")));
        assertFalse(filter.shouldNotFilter(new MockHttpServletRequest(
                "POST", "/api/v1/orders/1/confirm")));
    }

    @Test
    @DisplayName("shouldNotFilter: null URI 跳过")
    void shouldNotFilter_nullUri() {
        CachedBodyFilter filter = new CachedBodyFilter();
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(null);
        assertTrue(filter.shouldNotFilter(req));
    }

    @Test
    @DisplayName("doFilterInternal: 包装为可重复读取请求，body 可多次读取")
    void doFilterInternal_wrapsRepeatableAndInvokesChain() throws Exception {
        CachedBodyFilter filter = new CachedBodyFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/refunds");
        String body = "{\"orderId\":1,\"amount\":\"50.00\"}";
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        final RepeatableReadRequestWrapper[] captured = new RepeatableReadRequestWrapper[1];
        doAnswer(inv -> {
            captured[0] = inv.getArgument(0);
            return null;
        }).when(chain).doFilter(any(), eq(resp));

        filter.doFilter(req, resp, chain);
        verify(chain, times(1)).doFilter(any(), eq(resp));

        assertNotNull(captured[0]);
        // 拦截器读一次后，控制器仍能读到完整 body（可重复读取）
        assertEquals(body, captured[0].getCachedBodyAsString());
        String firstRead = new String(captured[0].getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String secondRead = new String(captured[0].getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(body, firstRead);
        assertEquals(body, secondRead, "二次读取不得为空（ContentCachingRequestWrapper 的缺陷）");
    }
}
