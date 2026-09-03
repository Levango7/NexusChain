package org.nexus.gateway.security;

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
 * Gateway JWT Bearer token 鉴权过滤器。
 *
 * <p>交付前审计回归修复（2026-09-03）：S1 修复给商户管理端点补
 * {@code @PreAuthorize("hasRole('ADMIN')")}，但 gateway 无认证组件产生
 * ROLE_ADMIN，SecurityContext 恒为匿名 → ADMIN 端点对所有人 403（死端点）。
 * 本过滤器补齐认证闭环：从 {@code Authorization: Bearer <jwt>} 头解析 JWT，
 * 校验签名 + 过期后构造 {@link UsernamePasswordAuthenticationToken}（含
 * {@code ROLE_<name>} authorities）填入 {@link SecurityContextHolder}，
 * 下游 Controller 的 {@code @PreAuthorize} 即可按角色放行。</p>
 *
 * <p>移植自 {@code nexus-signing-service} 的 JwtAuthenticationFilter
 * （gateway 侧无需 SIGNING_SERVICE→SIGNER 角色归一化，直接映射）。</p>
 *
 * <p>无 Bearer 头或 token 非法时本过滤器静默放行（不设置 Authentication），
 * 请求以匿名身份到达方法安全层 → 受 {@code @PreAuthorize} 保护的端点 403，
 * 公开端点（商户支付 API，由 ApiKeyInterceptor 鉴权）不受影响。</p>
 *
 * <p>继承 {@link OncePerRequestFilter} 保证单次请求只执行一次。</p>
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
                // 非法 token：清空上下文，请求以匿名到达方法安全层（受保护端点 403）
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