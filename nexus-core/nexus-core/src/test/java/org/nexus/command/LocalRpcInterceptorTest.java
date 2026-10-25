package org.nexus.command;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.nexus.util.JWTUtil;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LocalRpcInterceptor 加固回归（2026-10-25）。
 *
 * <p>锁定：① {@code localonly=false} 直接放行；② 拒绝时**显式 401 + JSON body**
 * （旧实现 return false 不写响应 ⇒ 200 空 body 静默失效）；③ 动态空串 token
 * 必须拒绝（旧实现引用比较 {@code token != ""} 的漏洞）；④ 畸形 token 不抛
 * 异常、按拒绝处理。</p>
 */
class LocalRpcInterceptorTest {

    private LocalRpcInterceptor interceptor(boolean localonly) {
        LocalRpcInterceptor i = new LocalRpcInterceptor();
        ReflectionTestUtils.setField(i, "localonly", localonly);
        return i;
    }

    private HttpServletResponse mockResponse(PrintWriter[] sink) throws java.io.IOException {
        HttpServletResponse response = mock(HttpServletResponse.class);
        PrintWriter pw = mock(PrintWriter.class);
        when(response.getWriter()).thenReturn(pw);
        sink[0] = pw;
        return response;
    }

    @Test
    void passesThroughWhenLocalOnlyDisabled() throws Exception {
        LocalRpcInterceptor i = interceptor(false);
        HttpServletRequest request = mock(HttpServletRequest.class);
        assertTrue(i.preHandle(request, mock(HttpServletResponse.class), new Object()),
                "localonly=false 时不拦截");
    }

    @Test
    void rejectsMissingTokenWithExplicit401() throws Exception {
        LocalRpcInterceptor i = interceptor(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        PrintWriter[] sink = new PrintWriter[1];
        HttpServletResponse response = mockResponse(sink);

        assertFalse(i.preHandle(request, response, new Object()));
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(sink[0]).write(anyString());
        verify(response).setContentType("application/json");
    }

    @Test
    void rejectsDynamicallyCreatedEmptyToken() throws Exception {
        LocalRpcInterceptor i = interceptor(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("token")).thenReturn(new String(""));
        PrintWriter[] sink = new PrintWriter[1];
        HttpServletResponse response = mockResponse(sink);

        assertFalse(i.preHandle(request, response, new Object()),
                "空串 token（非字面量）必须拒绝——旧实现引用比较会放行");
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void rejectsMalformedTokenWithoutThrowing() throws Exception {
        LocalRpcInterceptor i = interceptor(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("token")).thenReturn("not-a-jwt");
        PrintWriter[] sink = new PrintWriter[1];
        HttpServletResponse response = mockResponse(sink);

        assertFalse(i.preHandle(request, response, new Object()),
                "畸形 token：拒绝且不向调用方抛异常");
        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void acceptsValidJwt() throws Exception {
        // JWTUtil 静态密钥由 Spring init() 填充；单测里反射注入 + finally 复位（避免污染同 JVM 其他用例）
        ReflectionTestUtils.setField(JWTUtil.class, "APP_ID", "test-app-id");
        ReflectionTestUtils.setField(JWTUtil.class, "APP_SECRET", "test-app-secret-0123456789-abcdef");
        try {
            String token = org.nexus.util.JWTUtil.createJWT(60_000L);
            assertFalse(token == null || token.isEmpty(), "createJWT 应产出 token");

            LocalRpcInterceptor i = interceptor(true);
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getHeader("token")).thenReturn(token);
            PrintWriter[] sink = new PrintWriter[1];
            HttpServletResponse response = mockResponse(sink);

            assertTrue(i.preHandle(request, response, new Object()), "合法 JWT 应放行");
        } finally {
            ReflectionTestUtils.setField(JWTUtil.class, "APP_ID", null);
            ReflectionTestUtils.setField(JWTUtil.class, "APP_SECRET", null);
        }
    }
}
