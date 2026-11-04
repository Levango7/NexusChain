package org.nexus.command;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActuatorPrometheusAuthFilterTest {

    private static final String CURRENT = "current-token";
    private static final String PREVIOUS = "previous-token";

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

    private static ActuatorPrometheusAuthFilter filter(String current, String previous) {
        return new ActuatorPrometheusAuthFilter(current, previous);
    }

    @Test
    void unconfiguredTokenFailsClosed() throws Exception {
        ActuatorPrometheusAuthFilter filter = filter("", "");
        FilterChain chain = mock(FilterChain.class);
        PrintWriter[] sink = new PrintWriter[1];
        HttpServletResponse response = response(sink);

        filter.doFilter(request("/actuator/prometheus", null), response, chain);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(sink[0]).write(anyString());
        verify(chain, times(0)).doFilter(any(ServletRequest.class), any(ServletResponse.class));
    }

    @Test
    void unconfiguredCurrentFailsClosedEvenIfPreviousIsSet() throws Exception {
        ActuatorPrometheusAuthFilter filter = filter("", PREVIOUS);
        FilterChain chain = mock(FilterChain.class);
        PrintWriter[] sink = new PrintWriter[1];
        HttpServletResponse response = response(sink);

        filter.doFilter(request("/actuator/prometheus", "Bearer " + PREVIOUS), response, chain);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        verify(chain, times(0)).doFilter(any(ServletRequest.class), any(ServletResponse.class));
    }

    @Test
    void rejectsInvalidMetricsToken() throws Exception {
        ActuatorPrometheusAuthFilter filter = filter(CURRENT, PREVIOUS);
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
        ActuatorPrometheusAuthFilter filter = filter(CURRENT, PREVIOUS);
        FilterChain chain = mock(FilterChain.class);
        PrintWriter[] sink = new PrintWriter[1];
        HttpServletResponse response = response(sink);

        filter.doFilter(request("/actuator/prometheus", "bearer " + CURRENT), response, chain);

        verify(chain).doFilter(any(ServletRequest.class), any(ServletResponse.class));
    }

    @Test
    void acceptsPreviousTokenDuringRotation() throws Exception {
        ActuatorPrometheusAuthFilter filter = filter(CURRENT, PREVIOUS);
        FilterChain chain = mock(FilterChain.class);
        PrintWriter[] sink = new PrintWriter[1];
        HttpServletResponse response = response(sink);

        filter.doFilter(request("/actuator/prometheus", "Bearer " + PREVIOUS), response, chain);

        verify(chain).doFilter(any(ServletRequest.class), any(ServletResponse.class));
    }

    @Test
    void leavesHealthUnauthenticated() throws Exception {
        ActuatorPrometheusAuthFilter filter = filter(CURRENT, PREVIOUS);
        FilterChain chain = mock(FilterChain.class);
        PrintWriter[] sink = new PrintWriter[1];
        HttpServletResponse response = response(sink);

        filter.doFilter(request("/actuator/health", null), response, chain);

        verify(chain).doFilter(any(ServletRequest.class), any(ServletResponse.class));
    }
}
