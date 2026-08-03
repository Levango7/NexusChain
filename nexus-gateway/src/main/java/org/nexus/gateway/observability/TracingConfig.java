package org.nexus.gateway.observability;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * B2: Distributed tracing filter.
 * Generates/propagates traceId across service boundaries.
 * In production, replaced by Micrometer Tracing + Zipkin/Jaeger auto-instrumentation.
 */
@Configuration
public class TracingConfig {

    public static final String TRACE_HEADER = "X-NexusChain-Trace-Id";
    public static final String SPAN_HEADER = "X-NexusChain-Span-Id";

    @Bean
    public OncePerRequestFilter tracingFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String traceId = request.getHeader(TRACE_HEADER);
                if (traceId == null || traceId.isEmpty()) {
                    traceId = UUID.randomUUID().toString().replace("-", "");
                }
                String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

                // Set MDC for structured logging
                org.slf4j.MDC.put("traceId", traceId);
                org.slf4j.MDC.put("spanId", spanId);

                // Propagate in response
                response.setHeader(TRACE_HEADER, traceId);
                response.setHeader(SPAN_HEADER, spanId);

                try {
                    chain.doFilter(request, response);
                } finally {
                    org.slf4j.MDC.clear();
                }
            }
        };
    }
}