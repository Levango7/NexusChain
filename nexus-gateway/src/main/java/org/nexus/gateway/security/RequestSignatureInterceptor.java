package org.nexus.gateway.security;

import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A2: Request signature verification interceptor.
 *
 * <p>Validates an HMAC-SHA256 signature computed over the canonical request
 * (timestamp + nonce + method + path + body) and enforces anti-replay controls:
 * <ul>
 *   <li>Timestamp freshness: reject if {@code |now - ts| > 5 min}.</li>
 *   <li>Nonce uniqueness: reject a nonce seen within the replay window.</li>
 *   <li>Constant-time signature comparison to avoid timing side-channels.</li>
 * </ul></p>
 *
 * <p>Expected headers:
 * <ul>
 *   <li>{@code X-NexusChain-Timestamp}: unix millis</li>
 *   <li>{@code X-NexusChain-Nonce}: unique request ID</li>
 *   <li>{@code X-NexusChain-Signature}: HMAC-SHA256(timestamp + nonce + method + path + body, secret)</li>
 * </ul></p>
 *
 * <p>The signing secret is a server-side shared secret configured via
 * {@code nexus.security.requestSigningSecret}. The per-merchant API secret is stored
 * only as a SHA-256 hash in the database and therefore cannot be used to recompute an
 * HMAC; a shared secret is the supported key source for request signing.</p>
 */
@Component
public class RequestSignatureInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequestSignatureInterceptor.class);

    private static final String TIMESTAMP_HEADER = "X-NexusChain-Timestamp";
    private static final String NONCE_HEADER = "X-NexusChain-Nonce";
    private static final String SIGNATURE_HEADER = "X-NexusChain-Signature";

    /** 防重放窗口（毫秒），默认 3 分钟，范围 1~5 分钟。Wave 12 决策5：从硬编码改为配置注入。 */
    private final long replayWindowMs;
    /** nonce 最小长度（字节），默认 16 字节（128 位）。 */
    private final int nonceMinLengthBytes;

    private final String signingSecret;

    /**
     * 是否接受 v1（无分隔符拼接）签名。默认 true（兼容期）；生产迁移完成后置
     * {@code false} 仅接受 v2（长度前缀 canonical，抗字段边界碰撞）。
     * 短期项 #4（2026-09-17）。
     */
    private final boolean legacySignatureEnabled;

    /**
     * 防重放 nonce 存储（短期项 #4b）。prod profile 注入 Redis 版
     * （多副本共享、fail-closed）；其余 profile 内存版；无 bean 时内存兜底。
     */
    private final ReplayNonceStore nonceStore;

    public RequestSignatureInterceptor(@Value("${nexus.security.requestSigningSecret:}") String signingSecret) {
        this(signingSecret, true, 180000L, 16);
    }

    /**
     * 测试/显式注入构造器：可直接指定是否接受 v1 签名（内存 nonce 存储）。
     *
     * @param signingSecret           HMAC 共享密钥
     * @param legacySignatureEnabled  兼容期是否接受 v1 拼接签名
     */
    public RequestSignatureInterceptor(String signingSecret, boolean legacySignatureEnabled) {
        this(signingSecret, legacySignatureEnabled, 180000L, 16);
    }

    /**
     * 测试/显式注入构造器：可指定防重放窗口和 nonce 最小长度。
     *
     * @param signingSecret           HMAC 共享密钥
     * @param legacySignatureEnabled  兼容期是否接受 v1 拼接签名
     * @param replayWindowMs          防重放窗口（毫秒）
     * @param nonceMinLengthBytes     nonce 最小长度（字节）
     */
    public RequestSignatureInterceptor(String signingSecret, boolean legacySignatureEnabled,
                                        long replayWindowMs, int nonceMinLengthBytes) {
        this.signingSecret = signingSecret == null ? "" : signingSecret;
        this.legacySignatureEnabled = legacySignatureEnabled;
        this.replayWindowMs = Math.max(60000L, Math.min(300000L, replayWindowMs));
        this.nonceMinLengthBytes = Math.max(16, nonceMinLengthBytes);
        this.nonceStore = new InMemoryReplayNonceStore();
    }

    /**
     * Spring 装配构造器：prod profile 注入 Redis 版 nonce 存储
     * （多副本共享），其余 profile 内存版；无 bean 时内存兜底。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RequestSignatureInterceptor(
            @Value("${nexus.security.requestSigningSecret:}") String signingSecret,
            @Value("${nexus.security.signature-legacy-enabled:true}") boolean legacySignatureEnabled,
            @Value("${nexus.security.replay-window-ms:180000}") long replayWindowMs,
            @Value("${nexus.security.nonce-min-length-bytes:16}") int nonceMinLengthBytes,
            org.springframework.beans.factory.ObjectProvider<ReplayNonceStore> nonceStoreProvider) {
        this.signingSecret = signingSecret == null ? "" : signingSecret;
        this.legacySignatureEnabled = legacySignatureEnabled;
        this.replayWindowMs = Math.max(60000L, Math.min(300000L, replayWindowMs));
        this.nonceMinLengthBytes = Math.max(16, nonceMinLengthBytes);
        ReplayNonceStore provided = nonceStoreProvider.getIfAvailable();
        this.nonceStore = provided != null ? provided : new InMemoryReplayNonceStore();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String timestamp = request.getHeader(TIMESTAMP_HEADER);
        String nonce = request.getHeader(NONCE_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);

        // Signature is mandatory on the protected path.
        if (isBlank(signature)) {
            return reject(response, 40101, "Missing request signature");
        }

        // Timestamp freshness (anti-replay window).
        if (isBlank(timestamp)) {
            return reject(response, 40102, "Missing timestamp header");
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return reject(response, 40102, "Invalid timestamp header");
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > replayWindowMs) {
            return reject(response, 40103, "Request timestamp expired (" + (replayWindowMs / 1000) + "s window)");
        }

        // Nonce uniqueness (anti-replay). 存储注入（短期项 #4b）：prod=Redis 共享，
        // 其余=内存；Redis 故障时 fail-closed（register 返回 false = 视为重放拒绝）。
        if (isBlank(nonce)) {
            return reject(response, 40104, "Missing nonce header");
        }
        // nonce 长度校验（Wave 12 §5.2.1-2）：UTF-8 字节长度须 ≥ nonceMinLengthBytes
        int nonceByteLength = nonce.getBytes(StandardCharsets.UTF_8).length;
        if (nonceByteLength < nonceMinLengthBytes) {
            return reject(response, 40109, "Nonce too short (min " + nonceMinLengthBytes + " bytes)");
        }
        if (!nonceStore.register(nonce, replayWindowMs)) {
            return reject(response, 40106, "Replayed nonce");
        }

        // Resolve server-side shared secret. Fail closed if unconfigured.
        if (signingSecret.isEmpty()) {
            log.error("Request signing secret (nexus.security.requestSigningSecret) is not configured; rejecting signed request");
            return reject(response, 40105, "Signature verification unavailable");
        }

        // Canonical request: v2（长度前缀）优先；v1（无分隔符拼接）仅在兼容期接受。
        String method = request.getMethod();
        String path = request.getRequestURI();
        String body = readBody(request);
        boolean signatureOk;
        if (signature.startsWith("v2:")) {
            signatureOk = constantTimeEquals(
                    computeSignatureV2(timestamp, nonce, method, path, body, signingSecret), signature);
        } else {
            if (!legacySignatureEnabled) {
                log.warn("Rejected v1 (delimiter-free concatenation) request signature — "
                        + "signature-legacy-enabled=false; migrate clients to v2 canonical (length-prefixed)");
                return reject(response, 40108, "Legacy signature format rejected (v2 required)");
            }
            signatureOk = constantTimeEquals(
                    computeSignature(timestamp, nonce, method, path, body, signingSecret), signature);
        }

        if (!signatureOk) {
            return reject(response, 40107, "Signature mismatch");
        }
        return true;
    }

    private String readBody(HttpServletRequest request) throws IOException {
        if (request instanceof RepeatableReadRequestWrapper w) {
            // Body buffered up-front by CachedBodyFilter; re-readable by @RequestBody.
            return w.getCachedBodyAsString();
        }
        if (request instanceof ContentCachingRequestWrapper w) {
            // Reading the stream populates the wrapper's cache so the controller's
            // @RequestBody can still deserialize the same bytes afterwards.
            ServletInputStream in = w.getInputStream();
            byte[] buf = new byte[8192];
            while (in.read(buf) != -1) { /* populate cache */ }
            byte[] cached = w.getContentAsByteArray();
            return new String(cached, StandardCharsets.UTF_8);
        }
        return "";
    }

    private boolean reject(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\"}");
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Constant-time string comparison to avoid timing side-channels.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
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

    /**
     * Compute HMAC-SHA256 signature for a request（v1 拼接协议，向后兼容）.
     * Used by SDK clients to sign outgoing requests. Canonical form:
     *   timestamp + nonce + method + path + body
     *
     * <p><b>v1 协议缺陷（2026-09-17 短期项 #4）</b>：字段无分隔符拼接，
     * 字段边界可平移产生碰撞。新签名方应使用 {@link #computeSignatureV2}；
     * 本方法仅在服务端 {@code nexus.security.signature-legacy-enabled=true}（兼容期）仍被接受。</p>
     */
    public static String computeSignature(String timestamp, String nonce, String method, String path, String body, String secret) {
        try {
            String payload = (timestamp == null ? "" : timestamp)
                    + (nonce == null ? "" : nonce)
                    + (method == null ? "" : method)
                    + (path == null ? "" : path)
                    + (body != null ? body : "");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException("Signature computation failed", e);
        }
    }

    /**
     * v2 canonical 签名：字段带长度前缀，消除字段边界歧义。
     * canonical = "NXC2|" + len(ts)+":"+ts + "|" + len(nonce)+":"+nonce + "|" + len(method)+":"+method
     *   + "|" + len(path)+":"+path + "|" + len(body)+":"+body
     * 长度按 UTF-8 字节计，保证多语言实现字节级一致。任一字段变化（含 null↔""）
     * 均改变 canonical 串，无法通过移动字段边界构造碰撞。
     *
     * @return "v2:" + lowerHex(HMAC-SHA256(canonical, secret))
     */
    public static String computeSignatureV2(String timestamp, String nonce, String method, String path, String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(canonicalV2(timestamp, nonce, method, path, body).getBytes(StandardCharsets.UTF_8));
            return "v2:" + toHex(hash);
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException("Signature computation failed", e);
        }
    }

    /** v2 canonical 串（签名输入），供 SDK / 测试对照实现。 */
    public static String canonicalV2(String timestamp, String nonce, String method, String path, String body) {
        StringBuilder sb = new StringBuilder(64);
        sb.append("NXC2|");
        appendField(sb, timestamp);
        appendField(sb, nonce);
        appendField(sb, method);
        appendField(sb, path);
        appendField(sb, body);
        return sb.toString();
    }

    private static void appendField(StringBuilder sb, String value) {
        String v = value == null ? "" : value;
        sb.append(v.getBytes(StandardCharsets.UTF_8).length).append(':').append(v).append('|');
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
