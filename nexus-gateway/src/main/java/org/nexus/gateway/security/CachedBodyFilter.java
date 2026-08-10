package org.nexus.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/**
 * Wraps incoming requests to /api/v1/payments/** in a ContentCachingRequestWrapper so
 * that the RequestSignatureInterceptor can read the request body (needed for the HMAC
 * over method+path+body) without consuming the stream before the controller
 * deserializes it via @RequestBody.
 *
 * <p>Registered automatically as a Spring component; only the payments path is wrapped
 * (see shouldNotFilter), so other endpoints are unaffected.</p>
 */
@Component
public class CachedBodyFilter extends OncePerRequestFilter {

    private static final String PAYMENTS_PATH = "/api/v1/payments";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.startsWith(PAYMENTS_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request);
        chain.doFilter(wrapped, response);
    }
}
