package org.nexus.gateway.orchestration.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Webhook 投递 HMAC-SHA256 签名服务（P4-T5）。
 *
 * <p>用途：Webhook 投递时对 payload 计算 HMAC-SHA256 签名，通过
 * {@code X-NexusChain-Signature} 请求头携带，接收方可用签名验证投递完整性。
 *
 * <p>签名规则：
 * <ul>
 *   <li>对 payload 的 <strong>规范形式</strong>（sorted-key JSON）计算 HMAC-SHA256</li>
 *   <li>规范形式保证签名对称：发送方与接收方对同一 payload 计算出相同签名</li>
 *   <li>输出为 hex 编码（小写），便于 HTTP 头传输</li>
 *   <li>常量时间比较（{@link #verify}）防止时序侧信道攻击</li>
 * </ul>
 *
 * <p>密钥来源：{@code nexus.webhook.callback-secret}（与接收方共享）。
 * 若密钥未配置，{@link #sign} 返回空字符串，{@link #verify} 在空签名时返回 false。
 *
 * @since Phase 4 - P4-T5 Webhook 重试与死信队列增强
 */
@Component
public class WebhookSignatureService {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureService.class);

    /** HMAC 算法名。 */
    public static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 签名请求头名称。 */
    public static final String SIGNATURE_HEADER = "X-NexusChain-Signature";

    /** 确定性（sorted-key）JSON mapper，保证签名对称。 */
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * 对 payload 计算 HMAC-SHA256 签名。
     *
     * @param payload   Webhook payload（Map 形式）
     * @param secret    共享密钥（UTF-8）
     * @return hex 编码的签名（64 字符）；若 secret 为空则返回空字符串
     * @throws IllegalArgumentException 若 payload 为 null
     */
    public String sign(Map<String, Object> payload, String secret) {
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (secret == null || secret.isEmpty()) {
            log.warn("Webhook signing secret is empty; signature will be omitted");
            return "";
        }
        String canonical = canonicalize(payload);
        return computeHmacHex(canonical, secret);
    }

    /**
     * 对原始 JSON 字符串计算 HMAC-SHA256 签名。
     *
     * <p>用于已序列化的 payload（如从 DLQ 重投时），跳过 canonicalize 步骤。
     *
     * @param payloadJson   Webhook payload JSON 字符串
     * @param secret        共享密钥
     * @return hex 编码的签名；若 secret 为空则返回空字符串
     */
    public String signRaw(String payloadJson, String secret) {
        if (payloadJson == null) {
            throw new IllegalArgumentException("payloadJson must not be null");
        }
        if (secret == null || secret.isEmpty()) {
            return "";
        }
        return computeHmacHex(payloadJson, secret);
    }

    /**
     * 验证签名是否匹配。
     *
     * <p>使用常量时间比较，防止时序侧信道攻击。
     *
     * @param payload       Webhook payload
     * @param secret        共享密钥
     * @param signature     待验证的签名（来自请求头）
     * @return {@code true} 若签名匹配；{@code false} 若不匹配或参数缺失
     */
    public boolean verify(Map<String, Object> payload, String secret, String signature) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        String expected = sign(payload, secret);
        if (expected.isEmpty()) {
            return false;
        }
        return constantTimeEquals(expected, signature);
    }

    /**
     * 验证原始 JSON 字符串的签名。
     *
     * @param payloadJson   Webhook payload JSON 字符串
     * @param secret        共享密钥
     * @param signature     待验证的签名
     * @return {@code true} 若签名匹配
     */
    public boolean verifyRaw(String payloadJson, String secret, String signature) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        String expected = signRaw(payloadJson, secret);
        if (expected.isEmpty()) {
            return false;
        }
        return constantTimeEquals(expected, signature);
    }

    /**
     * 计算 HMAC-SHA256 并输出 hex 编码。
     */
    private String computeHmacHex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256 signature", e);
        }
    }

    /**
     * 将 payload 序列化为确定性（sorted-key）JSON 字符串。
     */
    private String canonicalize(Map<String, Object> payload) {
        try {
            return CANONICAL_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to canonicalize webhook payload", e);
        }
    }

    /**
     * 常量时间字符串比较，防止时序侧信道攻击。
     */
    static boolean constantTimeEquals(String a, String b) {
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
}