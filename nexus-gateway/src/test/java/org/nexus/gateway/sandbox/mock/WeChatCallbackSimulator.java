package org.nexus.gateway.sandbox.mock;

import org.nexus.gateway.sandbox.util.TestPlatformCertificateFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 微信支付回调通知模拟器 — 用于端到端测试回调处理全流程（验签→解密→状态更新）。
 *
 * <p>模拟微信支付平台发送回调通知的完整过程：
 * <ol>
 *   <li>使用 {@link TestPlatformCertificateFactory} 的私钥生成 RSA-SHA256 签名（模拟微信平台签名）</li>
 *   <li>使用 APIv3 密钥对 resource 做 AES-256-GCM 加密（与解密对称）</li>
 *   <li>构造完整的微信回调 body（含 id, event_type, resource 字段）</li>
 *   <li>提供获取签名头（Wechatpay-Timestamp, Wechatpay-Nonce, Wechatpay-Signature, Wechatpay-Serial）的方法</li>
 * </ol>
 *
 * <p>签名串格式：{@code timestamp\nnonce\nbody\n}，
 * 与 {@link org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil#verifyCallbackSignatureWithPlatformCert}
 * 的验签逻辑完全对称。</p>
 *
 * <p>注意：回调签名串格式为 {@code timestamp\nnonce\nbody\n}，
 * 与请求签名格式 {@code method\nurl\ntimestamp\nnonce\nbody\n} 不同，
 * 因此不能直接使用 {@code WeChatPaySignatureUtil.generateRsaSignature()}，
 * 而是使用与其底层 {@code rsaSign} 相同的 RSA-SHA256 签名逻辑。</p>
 *
 * <p>经验来源：2026-09-10-payment-provider-signature-algorithm-standard-conformance-audit
 * （微信支付 V3 使用 RSA-SHA256，非 HMAC）</p>
 */
public class WeChatCallbackSimulator {

    private final TestPlatformCertificateFactory certFactory;
    private final String apiV3Key;

    private static final String SHA256_WITH_RSA = "SHA256withRSA";
    private static final String RSA = "RSA";
    private static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;

    public WeChatCallbackSimulator(TestPlatformCertificateFactory certFactory, String apiV3Key) {
        this.certFactory = certFactory;
        this.apiV3Key = apiV3Key;
    }

    /**
     * 构造微信回调请求体（JSON）。
     *
     * <p>包含 id, create_time, resource_type, event_type, summary,
     * resource（ciphertext, nonce, associated_data）字段。
     * resource.ciphertext 使用 AES-256-GCM 加密，可被
     * {@link org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil#decryptResource}
     * 解密。</p>
     *
     * @param notificationId 回调通知 ID（如 "EV-2018022511223320873"）
     * @param outTradeNo     商户订单号
     * @param tradeState     交易状态（SUCCESS / NOTPAY / CLOSED 等）
     * @return 完整的微信回调 JSON body
     */
    public String buildCallbackBody(String notificationId, String outTradeNo, String tradeState) {
        // 1. 构造 resource 明文 JSON（微信支付回调通知的加密内容）
        String resourcePlaintext = buildResourcePlaintext(outTradeNo, tradeState);

        // 2. 生成 AES-256-GCM 加密参数
        String nonce = generateNonce();
        String associatedData = "transaction";

        // 3. AES-256-GCM 加密 resource 明文
        String ciphertext = aesGcmEncrypt(resourcePlaintext, nonce, associatedData);

        // 4. 构造完整回调 body JSON
        return buildBodyJson(notificationId, ciphertext, nonce, associatedData);
    }

    /**
     * 生成回调签名头。
     *
     * <p>使用平台证书私钥做 RSA-SHA256 签名，签名串格式为
     * {@code timestamp\nnonce\nbody\n}，与微信支付回调验签逻辑对称。
     * 返回的 headers 可直接用于模拟微信回调 HTTP 请求头。</p>
     *
     * @param body 回调请求体（由 {@link #buildCallbackBody} 生成）
     * @return 包含 Wechatpay-Timestamp, Wechatpay-Nonce, Wechatpay-Signature, Wechatpay-Serial 的 Map
     */
    public Map<String, String> buildSignatureHeaders(String body) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString().replace("-", "");

        // 构造签名串：timestamp\nnonce\nbody\n（与 verifyCallbackSignatureWithPlatformCert 对称）
        String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";

        // 使用平台证书私钥做 RSA-SHA256 签名（模拟微信平台签名）
        String signature = rsaSign(signContent, certFactory.getPrivateKeyBase64());

        Map<String, String> headers = new HashMap<>();
        headers.put("Wechatpay-Timestamp", timestamp);
        headers.put("Wechatpay-Nonce", nonce);
        headers.put("Wechatpay-Signature", signature);
        headers.put("Wechatpay-Serial", certFactory.getSerialNumber());
        return headers;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 构造微信支付回调 resource 明文 JSON。
     *
     * <p>包含 transaction_id, out_trade_no, trade_state, trade_type, amount 等字段，
     * 模拟微信支付成功回调通知的 resource 内容。</p>
     *
     * @param outTradeNo 商户订单号
     * @param tradeState 交易状态
     * @return resource 明文 JSON 字符串
     */
    private String buildResourcePlaintext(String outTradeNo, String tradeState) {
        String transactionId = "wx_tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return "{" +
                "\"transaction_id\":\"" + transactionId + "\"," +
                "\"out_trade_no\":\"" + outTradeNo + "\"," +
                "\"trade_state\":\"" + tradeState + "\"," +
                "\"trade_type\":\"NATIVE\"," +
                "\"bank_type\":\"CMB_CREDIT\"," +
                "\"user_repaid\":false," +
                "\"success_time\":\"" + OffsetDateTime.now().toString() + "\"," +
                "\"amount\":{" +
                "\"total\":100," +
                "\"payer_total\":100," +
                "\"currency\":\"CNY\"," +
                "\"payer_currency\":\"CNY\"" +
                "}" +
                "}";
    }

    /**
     * AES-256-GCM 加密（与 {@link WeChatPaySignatureUtil#decryptResource} 解密对称）。
     *
     * <p>使用 APIv3 密钥作为 AES-256 对称密钥，nonce 作为 GCM IV，
     * associated_data 作为 GCM AAD。加密输出包含密文和 GCM tag（128位），
     * Base64 编码后即为 resource.ciphertext。</p>
     *
     * @param plaintext      待加密的明文 JSON
     * @param nonce          GCM nonce（resource.nonce）
     * @param associatedData GCM 附加认证数据（resource.associated_data）
     * @return Base64 编码的密文（含 GCM tag）
     */
    private String aesGcmEncrypt(String plaintext, String nonce, String associatedData) {
        try {
            byte[] keyBytes = apiV3Key.getBytes(StandardCharsets.UTF_8);
            byte[] nonceBytes = nonce.getBytes(StandardCharsets.UTF_8);
            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);

            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonceBytes);

            Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            if (associatedData != null && !associatedData.isEmpty()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }

            byte[] cipherBytes = cipher.doFinal(plaintextBytes);
            return Base64.getEncoder().encodeToString(cipherBytes);
        } catch (Exception e) {
            throw new RuntimeException("AES-256-GCM 加密失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构造完整的微信回调 body JSON。
     *
     * @param notificationId  回调通知 ID
     * @param ciphertext      Base64 编码的加密 resource 内容
     * @param nonce           AES-256-GCM nonce
     * @param associatedData  AES-256-GCM associated_data
     * @return 完整的回调 body JSON 字符串
     */
    private String buildBodyJson(String notificationId, String ciphertext,
                                  String nonce, String associatedData) {
        return "{" +
                "\"id\":\"" + notificationId + "\"," +
                "\"create_time\":\"" + OffsetDateTime.now().toString() + "\"," +
                "\"resource_type\":\"encrypt-resource\"," +
                "\"event_type\":\"TRANSACTION.SUCCESS\"," +
                "\"summary\":\"支付成功\"," +
                "\"resource\":{" +
                "\"original_type\":\"transaction\"," +
                "\"algorithm\":\"AEAD_AES_256_GCM\"," +
                "\"ciphertext\":\"" + ciphertext + "\"," +
                "\"associated_data\":\"" + associatedData + "\"," +
                "\"nonce\":\"" + nonce + "\"" +
                "}" +
                "}";
    }

    /**
     * 生成 AES-256-GCM nonce（32 位十六进制字符串，模拟微信支付 nonce 格式）。
     *
     * @return 32 位十六进制 nonce 字符串
     */
    private String generateNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * RSA-SHA256 签名（使用 PKCS#8 私钥）。
     *
     * <p>与 {@link WeChatPaySignatureUtil} 中的 {@code rsaSign} 私有方法实现一致，
     * 使用平台证书私钥模拟微信支付平台签名。</p>
     *
     * @param data             待签名数据
     * @param privateKeyBase64 PKCS#8 Base64 编码的私钥
     * @return Base64 编码的签名
     */
    private String rsaSign(String data, String privateKeyBase64) {
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
}