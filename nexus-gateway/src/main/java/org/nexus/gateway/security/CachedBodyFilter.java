package org.nexus.gateway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Wraps incoming requests to HMAC-signed endpoints (/api/v1/payments|refunds|orders/**)
 * in a {@link RepeatableReadRequestWrapper} so that the RequestSignatureInterceptor can
 * read the request body (needed for the HMAC over method+path+body) and the controller
 * can still deserialize the same bytes via @RequestBody afterwards.
 *
 * <p>Registered automatically as a Spring component; only the signed paths are wrapped
 * (see shouldNotFilter), so other endpoints are unaffected.</p>
 *
 * <p>NOTE: the wrapped paths must stay in sync with the RequestSignatureInterceptor
 * path patterns in WebConfig. A signed endpoint whose body is not cached here would
 * verify every request with an empty-body canonical form, rejecting all legitimate
 * requests that carry a body (P1-3 follow-up fix).</p>
 */
@Component
public class CachedBodyFilter extends OncePerRequestFilter {

    private static final List<String> SIGNED_PATH_PREFIXES = List.of(
            "/api/v1/payments",
            "/api/v1/refunds",
            "/api/v1/orders");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return true;
        }
        return SIGNED_PATH_PREFIXES.stream().noneMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RepeatableReadRequestWrapper wrapped = new RepeatableReadRequestWrapper(request);
        chain.doFilter(wrapped, response);
    }
}
