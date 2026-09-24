package org.nexus.gateway.orchestration.connectors;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/**
 * 支付宝 RSA2 签名工具类 — 基于 SHA256WithRSA 算法。
 *
 * <p>支付宝开放平台使用 RSA2（SHA256WithRSA）进行请求签名和回调验签。
 * 签名规则：</p>
 * <ol>
 *   <li>将所有请求参数按 key 的 ASCII 升序排序</li>
 *   <li>拼接为 key1=value1&key2=value2&... 格式（空值参数不参与签名）</li>
 *   <li>用商户私钥做 SHA256WithRSA 签名</li>
 *   <li>Base64 编码签名值</li>
 * </ol>
 *
 * <p>验签规则：对回调通知参数做同样排序拼接，用支付宝公钥验证签名。</p>
 *
 * <p>不引入支付宝 SDK 依赖，仅使用 Java 标准库实现。
 * 密钥格式要求：商户私钥为 PKCS#8 Base64，支付宝公钥为 X.509 Base64。</p>
 */
public class AlipaySignatureUtil {

    private static final String SIGN_ALGORITHM = "SHA256withRSA";
    private static final String KEY_ALGORITHM = "RSA";

    /**
     * 生成支付宝请求签名。
     *
     * @param params           请求参数（不含 sign 和 sign_type）
     * @param merchantPrivateKey 商户私钥（PKCS#8 Base64 编码）
     * @return Base64 编码的签名
     */
    public static String generateSignature(Map<String, String> params, String merchantPrivateKey) {
        String signContent = buildSignContent(params);
        return rsaSign(signContent, merchantPrivateKey);
    }

    /**
     * 验证支付宝回调通知签名。
     *
     * @param params          回调通知参数（含 sign 和 sign_type）
     * @param alipayPublicKey 支付宝公钥（X.509 Base64 编码）
     * @return true 验签通过，false 验签失败
     */
    public static boolean verifyCallbackSignature(Map<String, String> params, String alipayPublicKey) {
        String sign = params.get("sign");
        if (sign == null || sign.isEmpty()) {
            return false;
        }
        // 构造验签内容：排除 sign 和 sign_type 参数
        Map<String, String> signParams = new LinkedHashMap<>(params);
        signParams.remove("sign");
        signParams.remove("sign_type");
        String signContent = buildSignContent(signParams);
        return rsaVerify(signContent, sign, alipayPublicKey);
    }

    /**
     * 构造签名内容：参数按 key ASCII 升序排序，拼接为 key=value 格式。
     * 空值参数不参与签名。
     */
    private static String buildSignContent(Map<String, String> params) {
        // 过滤空值并按 key 排序
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                keys.add(entry.getKey());
            }
        }
        Collections.sort(keys);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            String value = params.get(key);
            if (i > 0) {
                sb.append("&");
            }
            sb.append(key).append("=").append(value);
        }
        return sb.toString();
    }

    /**
     * RSA2 签名（SHA256withRSA）。
     */
    private static String rsaSign(String data, String privateKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

            Signature signature = Signature.getInstance(SIGN_ALGORITHM);
            signature.initSign(privateKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            byte[] signBytes = signature.sign();
            return Base64.getEncoder().encodeToString(signBytes);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException | SignatureException e) {
            throw new RuntimeException("RSA2 签名计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * RSA2 验签（SHA256withRSA）。
     */
    private static boolean rsaVerify(String data, String signBase64, String publicKeyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            Signature signature = Signature.getInstance(SIGN_ALGORITHM);
            signature.initVerify(publicKey);
            signature.update(data.getBytes(StandardCharsets.UTF_8));
            byte[] signBytes = Base64.getDecoder().decode(signBase64);
            return signature.verify(signBytes);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException | SignatureException e) {
            throw new RuntimeException("RSA2 验签失败: " + e.getMessage(), e);
        }
    }
}