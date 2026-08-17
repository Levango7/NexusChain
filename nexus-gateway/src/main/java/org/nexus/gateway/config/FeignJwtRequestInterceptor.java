package org.nexus.gateway.config;

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
 * Feign 请求拦截器：自动为出站 Feign 调用注入 JWT Bearer token（P1-F1 安全加固）。
 *
 * <p>背景：signing-service 已启用 Spring Security + JWT 鉴权，所有
 * {@code /api/**} 端点强制 {@code ROLE_SIGNING_SERVICE}。gateway 通过
 * Feign 调用 signing-service 时必须携带合法 JWT，否则 401。</p>
 *
 * <h3>工作机制</h3>
 * <ol>
 *   <li>启动时从 {@code nexus.security.jwt.secret} 读取共享密钥（与
 *       signing-service 同一 {@code JWT_SECRET} 环境变量）</li>
 *   <li>每次 Feign 调用前，签发一个短期 JWT（subject={@code nexus-gateway},
 *       roles={@code SIGNING_SERVICE}, TTL=5min），写入
 *       {@code Authorization: Bearer <jwt>} 头</li>
 *   <li>token 短期 + 每次新生成，避免长期 token 泄露风险；HS256 签名开销
 *       可忽略（~微秒级）</li>
 * </ol>
 *
 * <h3>配置</h3>
 * <ul>
 *   <li>{@code nexus.security.jwt.secret}：共享密钥，生产环境通过
 *       {@code JWT_SECRET} 环境变量注入</li>
 *   <li>{@code nexus.security.jwt.service-roles}：服务间 token 的角色列表，
 *       默认 {@code SIGNING_SERVICE}（与 signing-service TxController
 *       {@code @PreAuthorize} 匹配）</li>
 *   <li>{@code nexus.security.jwt.ttl-millis}：token TTL，默认 300000（5 分钟）</li>
 * </ul>
 *
 * <p>本拦截器作为 Spring Bean 注册后，Spring Cloud OpenFeign 会自动发现并
 * 应用到所有 FeignClient（{@code @FeignClient} 接口）。无需在
 * {@code feign.client.config.default.requestInterceptors} 显式声明。</p>
 */
@Component
public class FeignJwtRequestInterceptor implements RequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FeignJwtRequestInterceptor.class);

    /** Authorization 头名称。 */
    public static final String AUTHORIZATION_HEADER = "Authorization";

    /** Bearer 前缀。 */
    public static final String BEARER_PREFIX = "Bearer ";

    /** roles claim 名称（与 signing-service JwtTokenProvider.CLAIM_ROLES 对齐）。 */
    public static final String CLAIM_ROLES = "roles";

    /** 默认服务间 token TTL：5 分钟。 */
    public static final long DEFAULT_TTL_MILLIS = 300_000L;

    @Value("${nexus.security.jwt.secret:}")
    private String secret;

    @Value("${nexus.security.jwt.service-subject:nexus-gateway}")
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
            log.warn("nexus.security.jwt.secret 未配置，Feign JWT 拦截器将签发无效 token；"
                    + "signing-service 鉴权会拒绝请求。生产环境必须通过 JWT_SECRET 环境变量注入");
            secret = "nexus-gateway-dev-placeholder-secret-32bytes";
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
        log.info("FeignJwtRequestInterceptor 已启用：subject={}, roles={}, ttlMillis={}",
                serviceSubject, rolesList, ttlMillis);
    }

    @Override
    public void apply(RequestTemplate template) {
        // 已存在 Authorization 头时不覆盖（调用方显式注入优先）
        if (template.headers().containsKey(AUTHORIZATION_HEADER)) {
            return;
        }
        String token = generateToken();
        template.header(AUTHORIZATION_HEADER, BEARER_PREFIX + token);
    }

    /**
     * 签发一个短期服务间 JWT。
     */
    private String generateToken() {
        long nowMillis = System.currentTimeMillis();
        return Jwts.builder()
                .subject(serviceSubject)
                .issuedAt(new Date(nowMillis))
                .expiration(new Date(nowMillis + ttlMillis))
                .claim(CLAIM_ROLES, String.join(",", rolesList))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }
}