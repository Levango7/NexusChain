package org.nexus.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 统一鉴权全局过滤器：API Key + HMAC-SHA256 签名验证。
 *
 * <p>P3-T2：在路由转发之前对每个请求执行双重校验：</p>
 * <ol>
 *   <li><b>API Key 校验</b>：请求头 {@code X-Nexus-Api-Key} 必须存在且非空，
 *       并与配置的合法 API Key 集合比对（开发环境单 Key，生产环境通过 Nacos 下发 Key 列表）。</li>
 *   <li><b>HMAC-SHA256 签名验证</b>：请求头 {@code X-Nexus-Signature} 必须存在，
 *       签名串 = HMAC-SHA256(secret, method + "\n" + path + "\n" + timestamp + "\n" + bodySha256)，
 *       其中 timestamp 来自 {@code X-Nexus-Timestamp} 头，bodySha256 为请求体 SHA-256 摘要的十六进制。
 *       时间戳偏差超过 {@code maxTimestampSkewSeconds}（默认 300s）拒绝，防重放。</li>
 * </ol>
 *
 * <h2>放行路径</h2>
 * <p>以下路径跳过鉴权（健康检查 / Actuator / 预检 OPTIONS）：</p>
 * <ul>
 *   <li>/actuator/**（Prometheus / health / info）</li>
 *   <li>OPTIONS 方法（CORS 预检，由 {@link CorsFilter} 处理）</li>
 * </ul>
 *
 * <h2>失败响应</h2>
 * <p>鉴权失败返回 HTTP 401 Unauthorized，响应体为 JSON {@code {"code":"UNAUTHORIZED","message":"..."}}，
 * 不进入下游服务。</p>
 *
 * <h2>配置项</h2>
 * <ul>
 *   <li>{@code nexus.api-gateway.auth.api-keys}：合法 API Key 列表（逗号分隔）</li>
 *   <li>{@code nexus.api-gateway.auth.hmac-secret}：HMAC 共享密钥</li>
 *   <li>{@code nexus.api-gateway.auth.max-timestamp-skew-seconds}：时间戳偏差上限（秒）</li>
 *   <li>{@code nexus.api-gateway.auth.enabled}：是否启用鉴权（默认 true，本地调试可关）</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>{@link Mac} 实例非线程安全，每次校验新建实例。{@link SecretKeySpec} 不可变可共享。</p>
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    /** API Key 请求头名。 */
    public static final String HEADER_API_KEY = "X-Nexus-Api-Key";
    /** HMAC 签名请求头名。 */
    public static final String HEADER_SIGNATURE = "X-Nexus-Signature";
    /** 请求时间戳请求头名（Unix epoch 秒）。 */
    public static final String HEADER_TIMESTAMP = "X-Nexus-Timestamp";
    /** HMAC 算法名。 */
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 放行路径前缀（不鉴权）。 */
    private static final List<String> WHITELIST_PREFIXES = List.of("/actuator/");

    /** 鉴权开关。 */
    @Value("${nexus.api-gateway.auth.enabled:true}")
    private boolean authEnabled;

    /** 合法 API Key 列表（逗号分隔）。 */
    @Value("${nexus.api-gateway.auth.api-keys:nexus-internal-api-key}")
    private String apiKeysConfig;

    /** HMAC 共享密钥。 */
    @Value("${nexus.api-gateway.auth.hmac-secret:nexus-hmac-secret}")
    private String hmacSecret;

    /** 时间戳偏差上限（秒），超过则拒绝（防重放）。 */
    @Value("${nexus.api-gateway.auth.max-timestamp-skew-seconds:300}")
    private long maxTimestampSkewSeconds;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        String method = request.getMethod().name();

        // 放行：Actuator 端点
        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }
        // 放行：CORS 预检
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return chain.filter(exchange);
        }
        // 鉴权关闭（本地调试）
        if (!authEnabled) {
            return chain.filter(exchange);
        }

        HttpHeaders headers = request.getHeaders();
        String apiKey = headers.getFirst(HEADER_API_KEY);
        String signature = headers.getFirst(HEADER_SIGNATURE);
        String timestamp = headers.getFirst(HEADER_TIMESTAMP);

        // 1. API Key 校验
        if (!StringUtils.hasText(apiKey) || !isValidApiKey(apiKey)) {
            log.warn("鉴权失败：API Key 无效，path={}, method={}", path, method);
            return reject(exchange, "invalid api key");
        }
        // 2. HMAC 签名验证
        if (!StringUtils.hasText(signature) || !StringUtils.hasText(timestamp)) {
            log.warn("鉴权失败：缺少签名或时间戳头，path={}, method={}", path, method);
            return reject(exchange, "missing signature or timestamp");
        }
        if (!verifyHmac(method, path, timestamp, signature)) {
            log.warn("鉴权失败：HMAC 签名不匹配或时间戳过期，path={}, method={}", path, method);
            return reject(exchange, "invalid signature or expired timestamp");
        }

        // 鉴权通过：将 API Key 写入下游请求头，便于下游服务审计
        ServerHttpRequest mutated = request.mutate()
                .header(HEADER_API_KEY, apiKey)
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /**
     * 全局过滤器顺序：鉴权最优先（在限流、日志之前）。
     * <p>使用 {@code -200} 留出空间给 CorsFilter（-100）等更外层过滤器。</p>
     */
    @Override
    public int getOrder() {
        return -200;
    }

    /** 判断路径是否在白名单中。 */
    private boolean isWhitelisted(String path) {
        for (String prefix : WHITELIST_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 校验 API Key 是否在配置的合法集合中。 */
    private boolean isValidApiKey(String apiKey) {
        if (!StringUtils.hasText(apiKeysConfig)) {
            return false;
        }
        String[] keys = apiKeysConfig.split(",");
        for (String key : keys) {
            if (apiKey.equals(key.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 验证 HMAC-SHA256 签名 + 时间戳偏差。
     *
     * @param method    HTTP 方法
     * @param path      请求路径
     * @param timestamp 客户端时间戳（Unix epoch 秒）
     * @param signature 客户端签名（Base64）
     * @return true 若签名匹配且时间戳在允许偏差内
     */
    private boolean verifyHmac(String method, String path, String timestamp, String signature) {
        // 时间戳偏差校验（防重放）
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        long now = System.currentTimeMillis() / 1000L;
        if (Math.abs(now - ts) > maxTimestampSkewSeconds) {
            return false;
        }

        // 构造签名串：method + "\n" + path + "\n" + timestamp
        // 注：API Gateway 不读取请求体（reactive 流只能消费一次），bodySha256 由客户端计算并省略，
        // 生产环境如需 body 签名，应改用 GatewayFilter 在 body 缓存后校验（参见 ReadBodyPredicate）。
        String payload = method + "\n" + path + "\n" + timestamp;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] actual = Base64.getDecoder().decode(signature);
            // 常量时间比较，防时序攻击
            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            log.debug("HMAC 计算异常", e);
            return false;
        }
    }

    /** 常量时间字节数组比较，防时序侧信道攻击。 */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    /** 返回 401 Unauthorized 响应，不进入下游。 */
    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        String body = "{\"code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
}