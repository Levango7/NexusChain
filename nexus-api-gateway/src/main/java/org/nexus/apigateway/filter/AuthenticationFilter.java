package org.nexus.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一鉴权全局过滤器：API Key + HMAC-SHA256 签名验证 + nonce 防重放。
 *
 * <p>P1-D1：与 nexus-gateway {@code RequestSignatureInterceptor} 对齐统一鉴权协议。</p>
 *
 * <h2>请求头（X-NexusChain-* 前缀，与 nexus-gateway 一致）</h2>
 * <ul>
 *   <li>{@code X-NexusChain-ApiKey}：商户 API Key，必须在配置的合法集合中</li>
 *   <li>{@code X-NexusChain-Timestamp}：Unix epoch 毫秒</li>
 *   <li>{@code X-NexusChain-Nonce}：本次请求唯一随机串，用于防重放</li>
 *   <li>{@code X-NexusChain-Signature}：HMAC-SHA256(secret, timestamp + nonce + method + path + body) 的十六进制小写编码</li>
 * </ul>
 *
 * <h2>签名串规范（与 nexus-gateway 完全一致）</h2>
 * <pre>signature = lowerHex(HMAC-SHA256(secret, timestamp + nonce + method + path + body))</pre>
 * <p>其中 {@code timestamp}、{@code nonce}、{@code method}、{@code path}、{@code body} 直接字符串拼接（无分隔符），
 * {@code body} 为请求体 UTF-8 字符串（无 body 时为空串）。编码采用小写十六进制（与 nexus-gateway
 * {@code RequestSignatureInterceptor#computeSignature} 完全一致，不再使用 Base64）。</p>
 *
 * <h2>防重放</h2>
 * <ol>
 *   <li><b>时间戳新鲜度</b>：{@code |now - ts| > maxTimestampSkewSeconds} 拒绝（默认 300s）。</li>
 *   <li><b>Nonce 唯一性</b>：在 {@code replayWindowMs} 窗口内重复 nonce 拒绝。
 *       单实例使用 {@link ConcurrentHashMap} 内存缓存；多实例部署需替换为 Redis SET NX（参见部署文档）。</li>
 * </ol>
 *
 * <h2>兼容期（deprecated）</h2>
 * <p>在 {@code legacy-headers-enabled=true}（默认）时，同时接受旧 {@code X-Nexus-*} 头：
 * {@code X-Nexus-Api-Key} / {@code X-Nexus-Signature} / {@code X-Nexus-Timestamp}。
 * 命中旧头时打 WARN 日志并写入响应头 {@code X-NexusChain-Deprecated: true} 提示客户端尽快迁移。
 * 兼容期结束后将 {@code legacy-headers-enabled} 置 false 关闭旧头接受。</p>
 *
 * <h2>放行路径</h2>
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
 *   <li>{@code nexus.api-gateway.auth.enabled}：鉴权总开关（默认 true）</li>
 *   <li>{@code nexus.api-gateway.auth.api-keys}：合法 API Key 列表（逗号分隔）</li>
 *   <li>{@code nexus.api-gateway.auth.hmac-secret}：HMAC 共享密钥</li>
 *   <li>{@code nexus.api-gateway.auth.max-timestamp-skew-seconds}：时间戳偏差上限（秒，默认 300）</li>
 *   <li>{@code nexus.api-gateway.auth.replay-window-ms}：nonce 防重放窗口（毫秒，默认 300000）</li>
 *   <li>{@code nexus.api-gateway.auth.legacy-headers-enabled}：是否接受旧 X-Nexus-* 头（默认 true，兼容期）</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>{@link Mac} 实例非线程安全，每次校验新建实例。{@link SecretKeySpec} 不可变可共享。
 * {@link #seenNonces} 为 {@link ConcurrentHashMap}，多线程安全。</p>
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);

    // === 新头（X-NexusChain-*，与 nexus-gateway 对齐） ===
    /** API Key 请求头名。 */
    public static final String HEADER_API_KEY = "X-NexusChain-ApiKey";
    /** HMAC 签名请求头名。 */
    public static final String HEADER_SIGNATURE = "X-NexusChain-Signature";
    /** 请求时间戳请求头名（Unix epoch 毫秒）。 */
    public static final String HEADER_TIMESTAMP = "X-NexusChain-Timestamp";
    /** Nonce 请求头名（防重放随机串）。 */
    public static final String HEADER_NONCE = "X-NexusChain-Nonce";

    // === 旧头（X-Nexus-*，兼容期 deprecated） ===
    /** 旧 API Key 请求头名（deprecated）。 */
    public static final String LEGACY_HEADER_API_KEY = "X-Nexus-Api-Key";
    /** 旧 HMAC 签名请求头名（deprecated）。 */
    public static final String LEGACY_HEADER_SIGNATURE = "X-Nexus-Signature";
    /** 旧时间戳请求头名（deprecated）。 */
    public static final String LEGACY_HEADER_TIMESTAMP = "X-Nexus-Timestamp";

    /** 响应头：标记本次请求命中了 deprecated 旧头。 */
    public static final String HEADER_DEPRECATED = "X-NexusChain-Deprecated";

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

    /** 时间戳偏差上限（秒），超过则拒绝（防重放第一道）。 */
    @Value("${nexus.api-gateway.auth.max-timestamp-skew-seconds:300}")
    private long maxTimestampSkewSeconds;

    /** Nonce 防重放窗口（毫秒），过期 nonce 惰性驱逐。 */
    @Value("${nexus.api-gateway.auth.replay-window-ms:300000}")
    private long replayWindowMs;

    /** 是否接受旧 X-Nexus-* 头（兼容期，默认 true）。 */
    @Value("${nexus.api-gateway.auth.legacy-headers-enabled:true}")
    private boolean legacyHeadersEnabled;

    /**
     * Nonce -> expiry(ms)。已过期的 entry 惰性驱逐。
     * <p>单实例 MVP 使用内存 Map；多实例部署需替换为 Redis SET NX + TTL 共享存储。</p>
     */
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

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

        // 解析头（新头优先，兼容期回退旧头）
        HttpHeaders headers = request.getHeaders();
        AuthHeaders ah = resolveHeaders(headers);
        if (ah == null) {
            log.warn("鉴权失败：缺少鉴权头，path={}, method={}", path, method);
            return reject(exchange, "missing auth headers");
        }

        final String apiKey = ah.apiKey;
        final String signature = ah.signature;
        final String timestamp = ah.timestamp;
        final boolean legacy = ah.legacy;

        // 1. API Key 校验
        if (!StringUtils.hasText(apiKey) || !isValidApiKey(apiKey)) {
            log.warn("鉴权失败：API Key 无效，path={}, method={}, legacy={}", path, method, legacy);
            return reject(exchange, "invalid api key");
        }
        // 2. 签名/时间戳/nonce 头存在性校验
        if (!StringUtils.hasText(signature) || !StringUtils.hasText(timestamp)) {
            log.warn("鉴权失败：缺少签名或时间戳头，path={}, method={}", path, method);
            return reject(exchange, "missing signature or timestamp");
        }

        // 3. 时间戳偏差校验（防重放第一道，同步即可）
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            log.warn("鉴权失败：时间戳格式非法，ts={}, path={}", timestamp, path);
            return reject(exchange, "invalid timestamp");
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > maxTimestampSkewSeconds * 1000L) {
            log.warn("鉴权失败：时间戳过期，ts={}, now={}, path={}", ts, now, path);
            return reject(exchange, "expired timestamp");
        }

        // 4. 读取 body（reactive）→ 签名验证 → nonce 防重放 → 转发
        final String nonce = ah.nonce;
        return readAndCacheBody(exchange).flatMap(cached -> {
            String body = cached.body();
            // 签名验证
            if (!verifyHmac(timestamp, nonce, method, path, body, signature)) {
                log.warn("鉴权失败：HMAC 签名不匹配，path={}, method={}, legacy={}", path, method, legacy);
                return reject(exchange, "invalid signature");
            }
            // Nonce 防重放：新协议要求 nonce 必填；兼容期旧头无 nonce 字段，跳过防重放检查
            if (!legacy) {
                if (!StringUtils.hasText(nonce)) {
                    log.warn("鉴权失败：缺少 nonce 头，path={}, method={}", path, method);
                    return reject(exchange, "missing nonce");
                }
                long expiry = now + replayWindowMs;
                Long previous = seenNonces.put(nonce, expiry);
                if (previous != null && previous > now) {
                    log.warn("鉴权失败：nonce 重放，nonce={}, path={}", nonce, path);
                    return reject(exchange, "replayed nonce");
                }
                evictExpiredNonces(now);
            }

            // 鉴权通过：将 API Key 写入下游请求头，便于下游服务审计
            ServerHttpRequest mutated = cached.decorate(HEADER_API_KEY, apiKey);
            ServerWebExchange mutatedExchange = exchange.mutate().request(mutated).build();
            // 兼容期：命中旧头时标记 deprecated
            if (legacy) {
                mutatedExchange.getResponse().getHeaders().add(HEADER_DEPRECATED, "true");
            }
            return chain.filter(mutatedExchange);
        });
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
     * 解析鉴权头：新头优先，兼容期回退旧头。
     *
     * @return 解析结果；若新头和旧头都缺失则返回 null
     */
    private AuthHeaders resolveHeaders(HttpHeaders headers) {
        String apiKey = headers.getFirst(HEADER_API_KEY);
        String signature = headers.getFirst(HEADER_SIGNATURE);
        String timestamp = headers.getFirst(HEADER_TIMESTAMP);
        String nonce = headers.getFirst(HEADER_NONCE);
        if (StringUtils.hasText(apiKey) || StringUtils.hasText(signature) || StringUtils.hasText(timestamp)) {
            return new AuthHeaders(apiKey, signature, timestamp, nonce, false);
        }
        // 兼容期：回退旧头
        if (legacyHeadersEnabled) {
            String legacyKey = headers.getFirst(LEGACY_HEADER_API_KEY);
            String legacySig = headers.getFirst(LEGACY_HEADER_SIGNATURE);
            String legacyTs = headers.getFirst(LEGACY_HEADER_TIMESTAMP);
            if (StringUtils.hasText(legacyKey) || StringUtils.hasText(legacySig) || StringUtils.hasText(legacyTs)) {
                log.warn("命中 deprecated 旧头 X-Nexus-*，请迁移至 X-NexusChain-*；legacyKey={}",
                        legacyKey != null ? legacyKey.substring(0, Math.min(8, legacyKey.length())) + "***" : "null");
                // 旧头无 nonce 字段，兼容期允许 nonce 缺失（签名串中 nonce 段为空串）
                return new AuthHeaders(legacyKey, legacySig, legacyTs, null, true);
            }
        }
        return null;
    }

    /**
     * 读取并缓存请求体（reactive）。读取后通过 {@link ServerHttpRequestDecorator} 重新暴露 body，
     * 保证下游过滤器与路由仍能消费请求体。
     *
     * @return Mono<{@link CachedBody}>，body 为 UTF-8 字符串（无 body 时为空串）
     */
    private Mono<CachedBody> readAndCacheBody(ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        return DataBufferUtils.join(request.getBody())
                .map(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);
                    return new CachedBody(exchange, bytes);
                })
                .defaultIfEmpty(new CachedBody(exchange, new byte[0]));
    }

    /**
     * 验证 HMAC-SHA256 签名。
     *
     * @param timestamp 客户端时间戳
     * @param nonce     客户端 nonce（兼容期旧头可能为 null）
     * @param method    HTTP 方法
     * @param path      请求路径
     * @param body      请求体字符串
     * @param signature 客户端签名（小写十六进制）
     * @return true 若签名匹配
     */
    private boolean verifyHmac(String timestamp, String nonce, String method, String path, String body, String signature) {
        // 签名串：timestamp + nonce + method + path + body（直接拼接，与 nexus-gateway 一致）
        String payload = nullToEmpty(timestamp) + nullToEmpty(nonce)
                + nullToEmpty(method) + nullToEmpty(path) + (body != null ? body : "");
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String expectedHex = toLowerHex(expected);
            // 常量时间字符串比较，防时序攻击
            return constantTimeEquals(expectedHex, signature);
        } catch (Exception e) {
            log.debug("HMAC 计算异常", e);
            return false;
        }
    }

    /** 字节数组 → 小写十六进制字符串（与 nexus-gateway computeSignature 一致）。 */
    private static String toLowerHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 常量时间字符串比较，防时序侧信道攻击。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        int diff = ab.length ^ bb.length;
        int len = Math.min(ab.length, bb.length);
        for (int i = 0; i < len; i++) {
            diff |= (ab[i] ^ bb[i]);
        }
        for (int i = len; i < bb.length; i++) {
            diff |= bb[i];
        }
        for (int i = len; i < ab.length; i++) {
            diff |= ab[i];
        }
        return diff == 0;
    }

    /** 惰性驱逐过期 nonce。 */
    private void evictExpiredNonces(long now) {
        seenNonces.entrySet().removeIf(e -> e.getValue() < now);
    }

    /** 返回 401 Unauthorized 响应，不进入下游。 */
    private Mono<Void> reject(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("Content-Type", "application/json");
        String body = "{\"code\":\"UNAUTHORIZED\",\"message\":\"" + message + "\"}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))));
    }

    /** 鉴权头解析结果。 */
    private static final class AuthHeaders {
        final String apiKey;
        final String signature;
        final String timestamp;
        final String nonce;
        final boolean legacy;

        AuthHeaders(String apiKey, String signature, String timestamp, String nonce, boolean legacy) {
            this.apiKey = apiKey;
            this.signature = signature;
            this.timestamp = timestamp;
            this.nonce = nonce;
            this.legacy = legacy;
        }
    }

    /** 缓存的请求体 + 装饰器工厂。 */
    private static final class CachedBody {
        final ServerWebExchange exchange;
        final byte[] bytes;

        CachedBody(ServerWebExchange exchange, byte[] bytes) {
            this.exchange = exchange;
            this.bytes = bytes;
        }

        String body() {
            return new String(bytes, StandardCharsets.UTF_8);
        }

        /** 装饰原请求以重新暴露缓存的 body，并追加额外头。 */
        ServerHttpRequest decorate(String extraHeader, String extraValue) {
            ServerHttpRequest original = exchange.getRequest();
            org.springframework.core.io.buffer.DataBufferFactory bufferFactory =
                    exchange.getResponse().bufferFactory();
            ServerHttpRequest decorated = new ServerHttpRequestDecorator(original) {
                @Override
                public Flux<DataBuffer> getBody() {
                    if (bytes.length == 0) {
                        return Flux.empty();
                    }
                    DataBuffer buffer = bufferFactory.wrap(bytes);
                    return Flux.just(buffer);
                }
            };
            return decorated.mutate().header(extraHeader, extraValue).build();
        }
    }
}
