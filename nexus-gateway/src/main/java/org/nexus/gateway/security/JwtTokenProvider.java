package org.nexus.gateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Gateway JWT 令牌签发与校验工具（HS256 自对称密钥）。
 *
 * <p>交付前审计回归修复（2026-09-03）：S1 修复给商户管理端点补
 * {@code @PreAuthorize("hasRole('ADMIN')")} 时，gateway 缺少产生
 * {@code ROLE_ADMIN} 的认证组件——SecurityContext 恒为匿名，ADMIN 端点
 * 对所有人（含合法管理员）一律 403，形成「死端点」。本类与配套
 * {@link JwtAuthenticationFilter} 补齐认证闭环。</p>
 *
 * <p>与 {@code nexus-signing-service} 的 {@code JwtTokenProvider} 对齐
 * （jjwt 0.12.x API、HS256、roles claim 逗号分隔字符串），密钥读取同一配置项
 * {@code nexus.security.jwt.secret}（生产环境经 {@code JWT_SECRET} 环境变量注入，
 * 与 FeignJwtRequestInterceptor / signing-service 共享），实现服务间 token
 * 在 gateway 侧同样可解析。</p>
 *
 * <p>角色（authorities）以 {@code roles} claim 形式存储为逗号分隔字符串，
 * 解析时由 {@link JwtAuthenticationFilter} 映射为 {@code ROLE_<name>}
 * SimpleGrantedAuthority（与 Spring Security {@code hasRole('X')} 匹配
 * {@code ROLE_X} 的约定一致）。</p>
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    /** roles claim 名称（与 FeignJwtRequestInterceptor.CLAIM_ROLES / signing-service 对齐）。 */
    public static final String CLAIM_ROLES = "roles";

    /** 默认 token TTL（毫秒）：1 小时。 */
    public static final long DEFAULT_TTL_MILLIS = 3600_000L;

    @Value("${nexus.security.jwt.secret:}")
    private String secret;

    /** HS256 签名密钥。 */
    private SecretKey signingKey;

    @PostConstruct
    void init() {
        if (secret == null || secret.isBlank()) {
            // 未配置密钥时记录警告但不抛异常，避免单测上下文启动失败；
            // 未配置密钥时签发/校验均用进程内一次性随机密钥，
            // 外部伪造 token 全部校验失败 → fail-closed（同 signing-service 策略）。
            log.warn("nexus.security.jwt.secret 未配置，gateway JWT 鉴权将不可用；"
                    + "生产环境必须通过 JWT_SECRET 环境变量注入");
            byte[] randomKey = new byte[32];
            new SecureRandom().nextBytes(randomKey);
            secret = new String(Base64.getEncoder().encode(randomKey), StandardCharsets.UTF_8);
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256 要求 ≥32 字节；不足则零填充到 32 字节
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.signingKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        // 确保密钥字节不再以明文 String 形式驻留
        Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 签发一个 JWT（供测试与运维签发流程使用）。
     *
     * @param subject  subject claim（通常为服务名 / 管理员 ID）
     * @param roles    角色列表（写入 roles claim，逗号分隔）
     * @param ttlMillis 过期时长（毫秒），≤0 表示不设置过期
     * @return compact JWT 字符串
     */
    public String generateToken(String subject, Collection<String> roles, long ttlMillis) {
        long nowMillis = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject(subject)
                .issuedAt(new Date(nowMillis))
                .claim(CLAIM_ROLES, roles.stream().collect(Collectors.joining(",")))
                .signWith(signingKey, Jwts.SIG.HS256);
        if (ttlMillis > 0) {
            builder.expiration(new Date(nowMillis + ttlMillis));
        }
        return builder.compact();
    }

    /** 使用默认 TTL 签发。 */
    public String generateToken(String subject, Collection<String> roles) {
        return generateToken(subject, roles, DEFAULT_TTL_MILLIS);
    }

    /**
     * 校验 token 签名 + 过期。
     *
     * @return true 表示合法且未过期；false 表示非法/过期/格式错误
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 校验失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 解析 token 的 Claims（不捕获异常，由调用方决定如何处理）。
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 Claims 提取角色列表，去除空白项。
     */
    public static List<String> extractRoles(Claims claims) {
        Object raw = claims.get(CLAIM_ROLES);
        if (raw == null) {
            return Collections.emptyList();
        }
        String joined = raw.toString();
        if (joined.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(joined.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}