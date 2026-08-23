package org.nexus.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PostConstruct;
import jakarta.xml.bind.DatatypeConverter;
import java.util.Date;

/**
 * JWT 工具类。
 * REQ-11/P2: 适配 jjwt 0.12.x API（Jwts.parser().verifyWith().build() / parseSignedClaims().getPayload()）。
 *
 * <p>安全修复（P0/密钥管理）：原硬编码 APP_ID/APP_SECRET 已改为通过 Spring
 * {@code @Value} 注入，配置项为 {@code nexus.security.jwt.app-id} 与
 * {@code nexus.security.jwt.app-secret}。启动时通过 {@link #init()} 校验
 * 非空，缺失则 fail-closed 拒绝启动。</p>
 *
 * <p>为保持 {@code LocalRpcInterceptor}/{@code Fifo} 等调用方对静态方法
 * {@link #createJWT(long)} / {@link #parseJWT(String)} 的依赖不断，本类
 * 仍保留静态方法签名；注入的实例字段在 {@link #init()} 中赋值给静态字段，
 * 由 Spring 容器在 Bean 初始化阶段完成。调用方均为 Spring Bean，其方法
 * 在容器初始化完成后才被触发，因此静态字段此时已被填充。</p>
 *
 * @since 1.0
 */
@Component
public class JWTUtil {
    private static final Logger logger = LoggerFactory.getLogger(JWTUtil.class);

    /**
     * 静态字段——由 {@link #init()} 从注入的实例字段填充。
     * 保留 static 以维持 {@link #createJWT(long)} / {@link #parseJWT(String)} 静态方法签名。
     */
    private static String APP_ID = null;
    private static String APP_SECRET = null;

    private final static String id = "1";
    private final static String issuer = "admin";
    private final static String subject = "JWTToken";

    /**
     * 注入的 JWT App ID。
     * <p>优先从环境变量 {@code NEXUS_JWT_APP_ID} 读取（通过 application.yml
     * 中的 {@code ${NEXUS_JWT_APP_ID:}} 占位符转发）。</p>
     */
    @Value("${nexus.security.jwt.app-id:}")
    private String appId;

    /**
     * 注入的 JWT App Secret。
     * <p>优先从环境变量 {@code NEXUS_JWT_APP_SECRET} 读取。</p>
     */
    @Value("${nexus.security.jwt.app-secret:}")
    private String appSecret;

    /**
     * 启动时校验并填充静态字段。
     *
     * <p>fail-closed：若 {@code appId} 或 {@code appSecret} 为空，抛出
     * {@link IllegalStateException} 拒绝启动，避免使用空密钥签发/校验 JWT。</p>
     */
    @PostConstruct
    public void init() {
        if (appId == null || appId.isEmpty()) {
            throw new IllegalStateException(
                    "JWTUtil init failed: nexus.security.jwt.app-id is not configured. "
                            + "Set NEXUS_JWT_APP_ID environment variable or nexus.security.jwt.app-id property.");
        }
        if (appSecret == null || appSecret.isEmpty()) {
            throw new IllegalStateException(
                    "JWTUtil init failed: nexus.security.jwt.app-secret is not configured. "
                            + "Set NEXUS_JWT_APP_SECRET environment variable or nexus.security.jwt.app-secret property.");
        }
        APP_ID = appId;
        APP_SECRET = appSecret;
        logger.info("JWTUtil initialized with configured app-id/app-secret (lengths: appId={}, appSecret={})",
                APP_ID.length(), APP_SECRET.length());
    }

    //Sample method to construct a JWT
    public static String createJWT(long ttlMillis) {

        long nowMillis = System.currentTimeMillis();
        Date now = new Date(nowMillis);

        //We will sign our JWT with our ApiKey secret
        byte[] apiKeySecretBytes = DatatypeConverter.parseBase64Binary(APP_ID + APP_SECRET);
        // jjwt 0.12.x: 至少 32 字节才能用于 HS256；若 base64 解码后不足则补齐到 32 字节
        if (apiKeySecretBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(apiKeySecretBytes, 0, padded, 0, apiKeySecretBytes.length);
            apiKeySecretBytes = padded;
        }
        SecretKey signingKey = new SecretKeySpec(apiKeySecretBytes, "HmacSHA256");

        //Let's set the JWT Claims
        var builder = Jwts.builder().id(id)
                .issuedAt(now)
                .subject(subject)
                .issuer(issuer)
                .signWith(signingKey, Jwts.SIG.HS256);

        //if it has been specified, let's add the expiration
        if (ttlMillis >= 0) {
            long expMillis = nowMillis + ttlMillis;
            Date exp = new Date(expMillis);
            builder.expiration(exp);
        }

        //Builds the JWT and serializes it to a compact, URL-safe string
        return builder.compact();

    }

    //Sample method to validate and read the JWT
    public static boolean parseJWT(String jwt) {
        try {
            //This line will throw an exception if it is not a signed JWS (as expected)
            byte[] apiKeySecretBytes = DatatypeConverter.parseBase64Binary(APP_ID + APP_SECRET);
            if (apiKeySecretBytes.length < 32) {
                byte[] padded = new byte[32];
                System.arraycopy(apiKeySecretBytes, 0, padded, 0, apiKeySecretBytes.length);
                apiKeySecretBytes = padded;
            }
            SecretKey signingKey = new SecretKeySpec(apiKeySecretBytes, "HmacSHA256");
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
//            System.out.println("ID: " + claims.getId());
//            System.out.println("Subject: " + claims.getSubject());
//            System.out.println("Issuer: " + claims.getIssuer());
//            System.out.println("Expiration: " + claims.getExpiration());
        }catch (RuntimeException e){
            return false;
        }
        return true;
    }

//    public static void main(String[] args) {
//        long exp = 3600000;//过期时间为1h
//        System.out.println("create:"+createJWT(exp));
//
//        boolean claims = JWTUtil.parseJWT("eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOiIxIiwiaWF0IjoxNTcyNDE2MTYwLCJzdWIiOiJKV1RUb2tlbiIsImlzcyI6ImFkbWluIiwiZXhwIjoxNTcyNDE5NzYwfQ.oBfuzZVRxiDXiMOGBYdHHKHDJzu9P4Kdb-zdtaD-Jvo");
//        System.out.println(claims);
//    }
}
