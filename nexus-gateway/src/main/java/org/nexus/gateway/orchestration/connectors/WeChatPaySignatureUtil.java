package org.nexus.gateway.orchestration.connectors;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;

import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 微信支付 V3 签名工具类 — 基于 RSA-SHA256 算法，支持 AES-256-GCM 解密。
 *
 * <p>微信支付 V3 API 使用 RSA-SHA256 进行请求签名和回调验签，
 * 使用 AES-256-GCM 解密回调通知中的敏感数据。</p>
 *
 * <h3>请求签名（RSA-SHA256）</h3>
 * <p>签名串构造规则：</p>
 * <pre>
 *   HTTP方法\n
 *   URL（含查询参数）\n
 *   时间戳\n
 *   随机串\n
 *   请求体（GET 请求为空字符串）\n
 * </pre>
 * <p>使用商户 RSA 私钥对签名串做 SHA256withRSA 签名，再 Base64 编码。</p>
 *
 * <h3>回调验签（RSA-SHA256）</h3>
 * <p>验签串构造为：</p>
 * <pre>
 *   时间戳\n
 *   随机串\n
 *   请求体\n
 * </pre>
 * <p>使用微信平台证书公钥验签。</p>
 *
 * <h3>资源解密（AES-256-GCM）</h3>
 * <p>使用 APIv3 密钥作为 AES-256-GCM 对称密钥，解密 resource.ciphertext。</p>
 *
 * <p>仅使用 Java 标准库（JCA），不引入外部依赖。</p>
 *
 * <p>经验来源：2026-09-26-payment-channel-signature-java-stdlib-implementation、
 * 2026-09-10-payment-provider-signature-algorithm-standard-conformance-audit</p>
 */
public class WeChatPaySignatureUtil {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SHA256_WITH_RSA = "SHA256withRSA";
    private static final String RSA = "RSA";
    private static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;

    // ==================== 旧方法（向后兼容，已废弃） ====================

    /**
     * 生成微信支付 V3 请求签名（HMAC-SHA256）。
     *
     * @deprecated 微信支付 V3 应使用 RSA-SHA256 签名，请改用
     *             {@link #generateRsaSignature(String, String, String, String, String, String)}
     * @param method    HTTP 方法（GET/POST/PUT/DELETE）
     * @param url       请求 URL（含查询参数，不含域名）
     * @param timestamp 时间戳（秒级）
     * @param nonce     随机串
     * @param body      请求体（GET 请求传空字符串）
     * @param apiV3Key  APIv3 密钥
     * @return Base64 编码的签名
     */
    @Deprecated
    public static String generateSignature(String method, String url, String timestamp,
                                            String nonce, String body, String apiV3Key) {
        String signContent = buildSignContent(method, url, timestamp, nonce, body);
        return hmacSha256Base64(signContent, apiV3Key);
    }

    /**
     * 验证微信支付回调通知签名（HMAC-SHA256）。
     *
     * @deprecated 微信支付 V3 回调应使用平台证书公钥验签，请改用
     *             {@link #verifyCallbackSignatureWithPlatformCert(String, String, String, String, String)}
     * @param timestamp     回调时间戳（Wechatpay-Timestamp 头）
     * @param nonce         回调随机串（Wechatpay-Nonce 头）
     * @param body          回调请求体
     * @param signature     回调签名（Wechatpay-Signature 头）
     * @param apiV3Key      APIv3 密钥
     * @return true 验签通过，false 验签失败
     */
    @Deprecated
    public static boolean verifyCallbackSignature(String timestamp, String nonce,
                                                   String body, String signature, String apiV3Key) {
        String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";
        String computed = hmacSha256Base64(signContent, apiV3Key);
        return constantTimeEquals(computed, signature);
    }

    // ==================== 新方法：RSA-SHA256 请求签名 ====================

    /**
     * 生成微信支付 V3 请求签名（RSA-SHA256）。
     * 使用商户 RSA 私钥对签名串做 SHA256withRSA 签名，再 Base64 编码。
     *
     * @param method             HTTP 方法
     * @param url                请求 URL（含查询参数，不含域名）
     * @param timestamp          时间戳（秒级）
     * @param nonce              随机串
     * @param body               请求体（GET 请求传空字符串）
     * @param merchantPrivateKey 商户 RSA 私钥（PKCS#8 Base64 编码）
     * @return Base64 编码的签名
     */
    public static String generateRsaSignature(String method, String url, String timestamp,
                                               String nonce, String body, String merchantPrivateKey) {
        String signContent = buildSignContent(method, url, timestamp, nonce, body);
        return rsaSign(signContent, merchantPrivateKey);
    }

    // ==================== 新方法：平台证书 RSA-SHA256 回调验签 ====================

    /**
     * 验证微信支付回调通知签名（RSA-SHA256，使用微信平台证书公钥）。
     *
     * @param timestamp             回调时间戳（Wechatpay-Timestamp 头）
     * @param nonce                 回调随机串（Wechatpay-Nonce 头）
     * @param body                  回调请求体
     * @param signature             回调签名（Wechatpay-Signature 头）
     * @param platformCertPublicKey 微信平台证书公钥（X.509 Base64 编码）
     * @return true 验签通过，false 验签失败
     */
    public static boolean verifyCallbackSignatureWithPlatformCert(
            String timestamp, String nonce, String body, String signature, String platformCertPublicKey) {
        String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";
        return rsaVerify(signContent, signature, platformCertPublicKey);
    }

    // ==================== 新方法：AES-256-GCM 解密 ====================

    /**
     * 解密微信支付 V3 回调通知中的 resource.ciphertext。
     * 使用 APIv3 密钥作为 AES-256-GCM 对称密钥。
     *
     * @param ciphertext     Base64 编码的密文
     * @param nonce          GCM nonce（resource.nonce）
     * @param associatedData GCM 附加认证数据（resource.associated_data，可为空）
     * @param apiV3Key       APIv3 密钥（32字节）
     * @return 解密后的明文 JSON 字符串
     * @throws RuntimeException 解密失败时抛出（含 AEADBadTagException）
     */
    public static String decryptResource(String ciphertext, String nonce,
                                          String associatedData, String apiV3Key) {
        try {
            byte[] keyBytes = apiV3Key.getBytes(StandardCharsets.UTF_8);
            byte[] nonceBytes = nonce.getBytes(StandardCharsets.UTF_8);
            byte[] cipherBytes = Base64.getDecoder().decode(ciphertext);

            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonceBytes);

            Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            if (associatedData != null && !associatedData.isEmpty()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }

            byte[] decryptedBytes = cipher.doFinal(cipherBytes);
            return new String(decryptedBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("解密失败: " + e.getMessage(), e);
        }
    }

    // ==================== 私有辅助方法 ====================

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
     * RSA-SHA256 签名（使用 PKCS#8 私钥）。
     *
     * @param data             待签名数据
     * @param privateKeyBase64 PKCS#8 Base64 编码的私钥
     * @return Base64 编码的签名
     */
    private static String rsaSign(String data, String privateKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(RSA);
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            Signature signature = Signature.getInstance(SHA256_WITH_RSA);
            signature.initSign(privateKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));

            byte[] signBytes = signature.sign();
            return Base64.getEncoder().encodeToString(signBytes);
        } catch (Exception e) {
            throw new RuntimeException("RSA-SHA256 签名计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * RSA-SHA256 验签（使用 X.509 公钥）。
     *
     * @param data              待验签数据
     * @param signBase64        Base64 编码的签名
     * @param publicKeyBase64   X.509 Base64 编码的公钥
     * @return true 验签通过，false 验签失败
     */
    private static boolean rsaVerify(String data, String signBase64, String publicKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(RSA);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            byte[] signBytes = Base64.getDecoder().decode(signBase64);

            Signature signature = Signature.getInstance(SHA256_WITH_RSA);
            signature.initVerify(publicKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));

            return signature.verify(signBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * HMAC-SHA256 计算并 Base64 编码（旧方法保留，向后兼容）。
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