package org.nexus.command;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActuatorPrometheusAuthFilterTest {

    private HttpServletRequest request(String path, String authorization) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getHeader("Authorization")).thenReturn(authorization);
        return request;
    }

    private HttpServletResponse response(PrintWriter[] sink) throws java.io.IOException {
        HttpServletResponse response = mock(HttpServletResponse.class);
        PrintWriter writer = mock(PrintWriter.class);
        when(response.getWriter()).thenReturn(writer);
        sink[0] = writer;
        return response;
    }

    @Test
    void unconfiguredTokenFailsClosed() throws Exception {
        ActuatorPrometheusAuthFilter filter = new ActuatorPrometheusAuthFilter("");
        FilterChain chain = mock(FilterChain.class);
        PrintWriter[] sink = new PrintWriter[1];
        HttpServletResponse response = response(sink);

        filter.doFilter(request("/actuator/prometheus", null), response, chain);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(sink[0]).write(anyString());
        verify(chain, times(0)).doFilter(any(ServletRequest.class), any(ServletResponse.class));
    }

    @Test
    void rejectsInvalidMetricsToken() throws Exception {
        ActuatorPrometheusAuthFilter filter = new ActuatorPrometheusAuthFilter("expected-token");
        FilterChain chain = mock(FilterChain.class);
        PrintWriter[] sink = new PrintWriter[1];
        HttpServletResponse response = response(sink);

        filter.doFilter(request("/actuator/prometheus", "Bearer wrong-token"), response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(response).setHeader("WWW-Authenticate", "Bearer realm=\"actuator/prometheus\"");
        verify(sink[0]).write(anyString());
        verify(chain, times(0)).doFilter(any(ServletRequest.class), any(ServletResponse.class));
    }

    @Test
    void acceptsExpectedMetricsToken() throws Exception {
        ActuatorPrometheusAuthFilter filter = new ActuatorPrometheusAuthFilter("expected-token");
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        filter.doFilter(request("/actuator/prometheus", "bearer expected-token"), response, chain);

        verify(chain).doFilter(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void leavesHealthUnauthenticated() throws Exception {
        ActuatorPrometheusAuthFilter filter = new ActuatorPrometheusAuthFilter("expected-token");
        FilterChain chain = mock(FilterChain.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        filter.doFilter(request("/actuator/health", null), response, chain);

        verify(chain).doFilter(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }
}
