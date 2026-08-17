package org.nexus.signing.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT Bearer token 鉴权过滤器。
 *
 * <p>P1-F1 安全加固：从 {@code Authorization: Bearer <jwt>} 头解析 JWT，
 * 校验签名 + 过期后构造 {@link UsernamePasswordAuthenticationToken} 填入
 * {@link SecurityContextHolder}，下游 Controller 即可通过
 * {@code @PreAuthorize} 等注解执行方法级鉴权。</p>
 *
 * <p>无 Bearer 头或 token 非法时本过滤器静默放行（不设置 Authentication），
 * 由 Spring Security 后续 AuthorizationFilter 按 HttpSecurity 规则决定
 * 是否拒绝（401/403）。这样可让 actuator/health 等公开端点正常放行，
 * 同时对受保护端点强制 401。</p>
 *
 * <p>继承 {@link OncePerRequestFilter} 保证单次请求只执行一次，
 * 异步 dispatch / forward 不会重复鉴权。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** Authorization 头前缀。 */
    public static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null) {
            try {
                Claims claims = tokenProvider.parseClaims(token);
                String subject = claims.getSubject();
                List<String> roles = JwtTokenProvider.extractRoles(claims);
                var authorities = roles.stream()
                        .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                        .toList();
                var authentication = new UsernamePasswordAuthenticationToken(
                        subject, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                // 非法 token：清空上下文，交由后续 AuthorizationFilter 拒绝
                log.debug("JWT 解析失败，拒绝鉴权: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 从 Authorization 头提取 Bearer token。
     *
     * @return token 字符串；缺失或非 Bearer 模式时返回 null
     */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}