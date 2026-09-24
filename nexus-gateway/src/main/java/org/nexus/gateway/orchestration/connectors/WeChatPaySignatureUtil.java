package org.nexus.gateway.orchestration.connectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 微信支付 V3 签名工具类 — 基于 HMAC-SHA256 算法。
 *
 * <p>微信支付 V3 API 使用 HMAC-SHA256 进行请求签名和回调验签。
 * 签名串构造规则：</p>
 * <pre>
 *   HTTP方法\n
 *   URL（含查询参数）\n
 *   时间戳\n
 *   随机串\n
 *   请求体（GET 请求为空字符串）\n
 * </pre>
 *
 * <p>生成签名：对签名串做 HMAC-SHA256，再 Base64 编码。
 * 验证签名：对回调通知的签名串做同样计算，与回调中的 Wechatpay-Signature 头比较。</p>
 *
 * <p>不引入微信 SDK 依赖，仅使用 Java 标准库实现。</p>
 */
public class WeChatPaySignatureUtil {

    private static final String HMAC_SHA256 = "HmacSHA256";

    /**
     * 生成微信支付 V3 请求签名。
     *
     * @param method    HTTP 方法（GET/POST/PUT/DELETE）
     * @param url       请求 URL（含查询参数，不含域名）
     * @param timestamp 时间戳（秒级）
     * @param nonce     随机串
     * @param body      请求体（GET 请求传空字符串）
     * @param apiV3Key  APIv3 密钥
     * @return Base64 编码的签名
     */
    public static String generateSignature(String method, String url, String timestamp,
                                           String nonce, String body, String apiV3Key) {
        String signContent = buildSignContent(method, url, timestamp, nonce, body);
        return hmacSha256Base64(signContent, apiV3Key);
    }

    /**
     * 验证微信支付回调通知签名。
     *
     * <p>微信回调验签时，签名串构造为：</p>
     * <pre>
     *   时间戳\n
     *   随机串\n
     *   请求体\n
     * </pre>
     *
     * @param timestamp     回调时间戳（Wechatpay-Timestamp 头）
     * @param nonce         回调随机串（Wechatpay-Nonce 头）
     * @param body          回调请求体
     * @param signature     回调签名（Wechatpay-Signature 头）
     * @param apiV3Key      APIv3 密钥
     * @return true 验签通过，false 验签失败
     */
    public static boolean verifyCallbackSignature(String timestamp, String nonce,
                                                  String body, String signature, String apiV3Key) {
        String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";
        String computed = hmacSha256Base64(signContent, apiV3Key);
        return constantTimeEquals(computed, signature);
    }

    /**
     * 构造请求签名串。
     */
    private static String buildSignContent(String method, String url, String timestamp,
                                           String nonce, String body) {
        return method + "\n"
                + url + "\n"
                + timestamp + "\n"
                + nonce + "\n"
                + (body != null ? body : "") + "\n";
    }

    /**
     * HMAC-SHA256 计算并 Base64 编码。
     */
    private static String hmacSha256Base64(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKey);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmacBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 签名计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 常量时间比较，防止时序攻击。
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}