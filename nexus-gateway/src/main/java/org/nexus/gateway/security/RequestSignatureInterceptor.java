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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final long MAX_AGE_MS = 5 * 60 * 1000L; // 5 minutes
    private static final String TIMESTAMP_HEADER = "X-NexusChain-Timestamp";
    private static final String NONCE_HEADER = "X-NexusChain-Nonce";
    private static final String SIGNATURE_HEADER = "X-NexusChain-Signature";

    private final String signingSecret;

    /**
     * Nonce -> expiry (ms). Entries older than the replay window are evicted lazily.
     * NOTE: in a multi-instance deployment this must be backed by shared storage
     * (e.g. Redis SET NX with TTL); in-memory is acceptable for single-instance MVP.
     */
    private final Map<String, Long> seenNonces = new ConcurrentHashMap<>();

    public RequestSignatureInterceptor(@Value("${nexus.security.requestSigningSecret:}") String signingSecret) {
        this.signingSecret = signingSecret == null ? "" : signingSecret;
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
        if (Math.abs(now - ts) > MAX_AGE_MS) {
            return reject(response, 40103, "Request timestamp expired (5min window)");
        }

        // Nonce uniqueness (anti-replay).
        if (isBlank(nonce)) {
            return reject(response, 40104, "Missing nonce header");
        }
        long expiry = now + MAX_AGE_MS;
        Long previous = seenNonces.put(nonce, expiry);
        if (previous != null && previous > now) {
            return reject(response, 40106, "Replayed nonce");
        }
        evictExpiredNonces(now);

        // Resolve server-side shared secret. Fail closed if unconfigured.
        if (signingSecret.isEmpty()) {
            log.error("Request signing secret (nexus.security.requestSigningSecret) is not configured; rejecting signed request");
            return reject(response, 40105, "Signature verification unavailable");
        }

        // Canonical request: timestamp + nonce + method + path + body.
        String method = request.getMethod();
        String path = request.getRequestURI();
        String body = readBody(request);
        String expected = computeSignature(timestamp, nonce, method, path, body, signingSecret);

        if (!constantTimeEquals(expected, signature)) {
            return reject(response, 40107, "Signature mismatch");
        }
        return true;
    }

    private String readBody(HttpServletRequest request) throws IOException {
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

    private void evictExpiredNonces(long now) {
        seenNonces.entrySet().removeIf(e -> e.getValue() < now);
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
     * Compute HMAC-SHA256 signature for a request.
     * Used by SDK clients to sign outgoing requests. Canonical form:
     *   timestamp + nonce + method + path + body
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
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new RuntimeException("Signature computation failed", e);
        }
    }
}
