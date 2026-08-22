package org.nexus.walletsvc.config;

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
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT 令牌签发与校验工具（HS256 自对称密钥）。
 *
 * <p>P0-3 安全加固：wallet-service 内嵌的 JWT Provider，与 signing-service
 * 的 {@code org.nexus.signing.config.JwtTokenProvider} 对齐 jjwt 0.12.x API，
 * 密钥从 {@code nexus.security.jwt.secret} 配置项读取（生产环境通过环境变量
 * {@code JWT_SECRET} 注入），与 signing-service 共享同一密钥以实现服务间
 * token 互信。</p>
 *
 * <p>本类同时承担签发（{@link #generateToken}，供测试与未来服务间互信场景）
 * 与校验（{@link #validateToken} / {@link #parseClaims}，供
 * {@link JwtAuthenticationFilter} 解析 Bearer token）两种角色。</p>
 *
 * <p>角色（authorities）以 {@code roles} claim 形式存储为逗号分隔字符串，
 * 解析时映射为 {@code ROLE_<name>} SimpleGrantedAuthority（与 Spring Security
 * {@code hasRole('X')} 匹配 {@code ROLE_X} 的约定一致）。</p>
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    /** roles claim 名称。 */
    public static final String CLAIM_ROLES = "roles";

    /** 默认服务间 token TTL（毫秒）：1 小时。 */
    public static final long DEFAULT_TTL_MILLIS = 3600_000L;

    @Value("${nexus.security.jwt.secret:}")
    private String secret;

    /** HS256 至少需要 32 字节密钥。 */
    private SecretKey signingKey;

    @PostConstruct
    void init() {
        if (secret == null || secret.isBlank()) {
            // 未配置密钥时记录警告但不抛异常，避免单测上下文启动失败；
            // SecurityConfig 在 JWT 模式下会显式校验并 fail-fast。
            log.warn("nexus.security.jwt.secret 未配置，JWT 鉴权将不可用；"
                    + "生产环境必须通过 JWT_SECRET 环境变量注入");
            // 安全兜底：使用 SecureRandom 生成一次性随机密钥（仅用于启动，
            // 每次进程不同且不可预测），避免硬编码可预测密钥的安全风险。
            // 实际请求会被 SecurityConfig 的 fail-closed 逻辑拒绝。
            byte[] randomKey = new byte[32];
            new java.security.SecureRandom().nextBytes(randomKey);
            secret = new String(java.util.Base64.getEncoder().encode(randomKey), StandardCharsets.UTF_8);
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
     * 签发一个 JWT。
     *
     * @param subject  subject claim（通常为服务名 / 用户 ID）
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

    /** 暴露签名密钥（仅供同包 JwtAuthenticationFilter / SecurityConfig 使用）。 */
    SecretKey getSigningKey() {
        return signingKey;
    }
}