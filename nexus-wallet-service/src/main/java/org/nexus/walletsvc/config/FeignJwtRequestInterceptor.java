package org.nexus.walletsvc.config;

import feign.RequestInterceptor;
import feign.RequestTemplate;
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
import java.util.Date;
import java.util.List;

/**
 * wallet-service 出向 Feign 的 JWT 注入。
 *
 * <p>此前只有 nexus-gateway 有同名拦截器：wallet 调 signing 的
 * {@code /transfers/sign} 与 A2 {@code /api/v1/signing/business-approvals}
 * 都不带 Authorization，而 signing 两处都要求 {@code ROLE_SIGNER} ⇒ 恒 403
 * （A2 只能回落 legacy，legacy 同样 403，提现必失败）。约定与 gateway 一致：
 * subject=服务名、roles 逗号分隔、HS256 共享密钥、token 5 分钟。</p>
 *
 * <p>默认 roles 取 {@code SIGNING_SERVICE}：signing 侧 JwtAuthenticationFilter
 * 会把它归一化成 {@code SIGNER}（P2-F1 遗留角色兼容），因此两侧无需各自钉死角色名。</p>
 */
@Component
public class FeignJwtRequestInterceptor implements RequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FeignJwtRequestInterceptor.class);

    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String CLAIM_ROLES = "roles";
    public static final long DEFAULT_TTL_MILLIS = 300_000L;

    @Value("${nexus.security.jwt.secret:}")
    private String secret;

    @Value("${nexus.security.jwt.service-subject:nexus-wallet-service}")
    private String serviceSubject;

    @Value("${nexus.security.jwt.service-roles:SIGNING_SERVICE}")
    private String serviceRoles;

    @Value("${nexus.security.jwt.ttl-millis:" + DEFAULT_TTL_MILLIS + "}")
    private long ttlMillis;

    private SecretKey signingKey;
    private List<String> rolesList;

    @PostConstruct
    void init() {
        if (secret == null || secret.isBlank()) {
            byte[] randomKey = new byte[32];
            new java.security.SecureRandom().nextBytes(randomKey);
            secret = java.util.Base64.getEncoder().encodeToString(randomKey);
            log.warn("nexus.security.jwt.secret 未配置，Feign JWT 拦截器签发的 token 不会被 signing 接受");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            keyBytes = padded;
        }
        this.signingKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        Keys.hmacShaKeyFor(keyBytes);
        this.rolesList = Arrays.stream(serviceRoles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        log.info("wallet FeignJwtRequestInterceptor 已启用：subject={}, roles={}, ttlMillis={}",
                serviceSubject, rolesList, ttlMillis);
    }

    @Override
    public void apply(RequestTemplate template) {
        if (template.headers().containsKey(AUTHORIZATION_HEADER)) {
            return;
        }
        template.header(AUTHORIZATION_HEADER, BEARER_PREFIX + generateToken());
    }

    private String generateToken() {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(serviceSubject)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .claim(CLAIM_ROLES, String.join(",", rolesList))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }
}
