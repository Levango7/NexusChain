package org.nexus.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link CachedBodyFilter} 单元测试：验证 shouldNotFilter 路径判定与 doFilterInternal 包装。
 */
class CachedBodyFilterTest {

    @Test
    @DisplayName("shouldNotFilter: 非 /api/v1/payments 路径跳过")
    void shouldNotFilter_otherPath() {
        CachedBodyFilter filter = new CachedBodyFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/orders");
        assertTrue(filter.shouldNotFilter(req));
    }

    @Test
    @DisplayName("shouldNotFilter: /api/v1/payments 路径不跳过")
    void shouldNotFilter_paymentsPath() {
        CachedBodyFilter filter = new CachedBodyFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/payments");
        assertFalse(filter.shouldNotFilter(req));
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
    @DisplayName("doFilterInternal: 调用 chain.doFilter 一次")
    void doFilterInternal_invokesChain() throws Exception {
        CachedBodyFilter filter = new CachedBodyFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/payments");
        req.setContent("{}".getBytes());
        MockHttpServletResponse resp = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(req, resp, chain);
        verify(chain, times(1)).doFilter(any(), eq(resp));
    }
}