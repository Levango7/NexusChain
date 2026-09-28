package org.nexus.gateway.sandbox.callback;

import org.nexus.gateway.orchestration.connectors.AlipaySignatureUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 回调模拟器服务 — 构造模拟回调通知并发送到内部回调端点。
 *
 * <p>支持两种回调模拟：</p>
 * <ol>
 *   <li>微信支付回调 — 构造完整的微信 V3 回调 JSON body（含 AES-256-GCM 加密 resource），
 *       使用平台私钥生成 RSA-SHA256 签名头，POST 到 {@code /api/v1/callbacks/wechat}</li>
 *   <li>支付宝回调 — 构造支付宝异步通知 form 表单参数（含 RSA2 签名），
 *       POST 到 {@code /api/v1/callbacks/alipay}</li>
 * </ol>
 *
 * <p>签名/加密逻辑与
 * {@link org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil} 和
 * {@link AlipaySignatureUtil} 的验签/解密逻辑完全对称，
 * 确保模拟回调可以通过回调端点的验签流程。</p>
 *
 * <p>经验来源：2026-09-10-payment-provider-signature-algorithm-standard-conformance-audit
 * （微信支付 V3 使用 RSA-SHA256，非 HMAC）</p>
 */
@Service
@Profile("sandbox")
public class CallbackSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(CallbackSimulatorService.class);

    private static final String SHA256_WITH_RSA = "SHA256withRSA";
    private static final String RSA = "RSA";
    private static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;

    /** 微信回调端点路径 */
    private static final String WECHAT_CALLBACK_PATH = "/api/v1/callbacks/wechat";

    /** 支付宝回调端点路径 */
    private static final String ALIPAY_CALLBACK_PATH = "/api/v1/callbacks/alipay";

    private final SandboxCallbackKeys callbackKeys;
    private final RestTemplate restTemplate;

    /**
     * 构造器注入。
     *
     * @param callbackKeys 沙盒回调密钥持有者
     * @param restTemplate HTTP 客户端（Spring 容器管理的 Bean）
     */
    public CallbackSimulatorService(SandboxCallbackKeys callbackKeys, RestTemplate restTemplate) {
        this.callbackKeys = callbackKeys;
        this.restTemplate = restTemplate;
    }

    // ==================== 微信支付回调模拟 ====================

    /**
     * 模拟微信支付回调通知。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>构造 resource 明文 JSON（含 out_trade_no, trade_state 等）</li>
     *   <li>使用 APIv3 密钥做 AES-256-GCM 加密</li>
     *   <li>构造完整的微信回调 body JSON（含 id, resource 字段）</li>
     *   <li>使用平台私钥生成 RSA-SHA256 签名头</li>
     *   <li>POST 到 {@code /api/v1/callbacks/wechat}</li>
     * </ol>
     *
     * @param outTradeNo 商户订单号
     * @param tradeState 交易状态（SUCCESS / NOTPAY / CLOSED 等）
     * @param baseUrl    应用基础 URL（如 {@code http://localhost:8080}）
     * @return 模拟结果 Map，包含 requestBody、responseBody、statusCode
     */
    public Map<String, Object> simulateWeChatCallback(String outTradeNo, String tradeState, String baseUrl) {
        log.info("[CallbackSimulator] 模拟微信回调: outTradeNo={}, tradeState={}", outTradeNo, tradeState);

        // 1. 构造 resource 明文 JSON
        String resourcePlaintext = buildWeChatResourcePlaintext(outTradeNo, tradeState);

        // 2. AES-256-GCM 加密
        String nonce = generateNonce();
        String associatedData = "transaction";
        String ciphertext = aesGcmEncrypt(resourcePlaintext, nonce, associatedData);

        // 3. 构造完整回调 body JSON
        String notificationId = "EV-" + System.currentTimeMillis();
        String body = buildWeChatBodyJson(notificationId, ciphertext, nonce, associatedData);

        // 4. 生成签名头
        Map<String, String> headers = buildWeChatSignatureHeaders(body);

        // 5. 发送 HTTP 请求
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        headers.forEach(httpHeaders::set);

        HttpEntity<String> entity = new HttpEntity<>(body, httpHeaders);
        String url = baseUrl + WECHAT_CALLBACK_PATH;

        log.info("[CallbackSimulator] 发送微信模拟回调到: {}", url);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, String.class);

        // 6. 构造返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "wechat");
        result.put("outTradeNo", outTradeNo);
        result.put("tradeState", tradeState);
        result.put("requestBody", body);
        result.put("requestHeaders", headers);
        result.put("responseStatus", response.getStatusCode().value());
        result.put("responseBody", response.getBody());

        log.info("[CallbackSimulator] 微信模拟回调完成: status={}", response.getStatusCode().value());
        return result;
    }

    // ==================== 支付宝回调模拟 ====================

    /**
     * 模拟支付宝异步回调通知。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>构造业务参数（app_id, out_trade_no, trade_no, trade_status, notify_id）</li>
     *   <li>使用商户私钥对业务参数生成 RSA2 签名</li>
     *   <li>添加 sign 和 sign_type=RSA2 参数</li>
     *   <li>POST form 表单到 {@code /api/v1/callbacks/alipay}</li>
     * </ol>
     *
     * @param outTradeNo  商户订单号
     * @param tradeStatus 交易状态（TRADE_SUCCESS / WAIT_BUYER_PAY / TRADE_CLOSED 等）
     * @param baseUrl     应用基础 URL（如 {@code http://localhost:8080}）
     * @return 模拟结果 Map，包含 requestParams、responseBody、statusCode
     */
    public Map<String, Object> simulateAlipayCallback(String outTradeNo, String tradeStatus, String baseUrl) {
        log.info("[CallbackSimulator] 模拟支付宝回调: outTradeNo={}, tradeStatus={}", outTradeNo, tradeStatus);

        // 1. 构造业务参数（不含 sign 和 sign_type）
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", callbackKeys.getAlipayAppId());
        params.put("out_trade_no", outTradeNo);
        params.put("trade_no", "alipay_tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        params.put("trade_status", tradeStatus);
        params.put("notify_id", String.valueOf(System.currentTimeMillis())
                + ThreadLocalRandom.current().nextInt(10000));

        // 2. 使用商户私钥对业务参数生成 RSA2 签名
        String sign = AlipaySignatureUtil.generateSignature(
                params, callbackKeys.getAlipayMerchantPrivateKeyBase64());

        // 3. 添加 sign 和 sign_type 参数
        params.put("sign", sign);
        params.put("sign_type", "RSA2");

        // 4. 发送 HTTP form 表单请求
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> formParams = new LinkedMultiValueMap<>();
        params.forEach(formParams::add);

        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(formParams, httpHeaders);
        String url = baseUrl + ALIPAY_CALLBACK_PATH;

        log.info("[CallbackSimulator] 发送支付宝模拟回调到: {}", url);
        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.POST, entity, String.class);

        // 5. 构造返回结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "alipay");
        result.put("outTradeNo", outTradeNo);
        result.put("tradeStatus", tradeStatus);
        result.put("requestParams", params);
        result.put("responseStatus", response.getStatusCode().value());
        result.put("responseBody", response.getBody());

        log.info("[CallbackSimulator] 支付宝模拟回调完成: status={}", response.getStatusCode().value());
        return result;
    }

    // ==================== 微信回调构造辅助方法 ====================

    /**
     * 构造微信支付回调 resource 明文 JSON。
     *
     * @param outTradeNo 商户订单号
     * @param tradeState 交易状态
     * @return resource 明文 JSON 字符串
     */
    private String buildWeChatResourcePlaintext(String outTradeNo, String tradeState) {
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
     * 构造完整的微信回调 body JSON。
     *
     * @param notificationId  回调通知 ID
     * @param ciphertext      Base64 编码的加密 resource 内容
     * @param nonce           AES-256-GCM nonce
     * @param associatedData  AES-256-GCM associated_data
     * @return 完整的回调 body JSON 字符串
     */
    private String buildWeChatBodyJson(String notificationId, String ciphertext,
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
     * 生成微信回调签名头。
     *
     * <p>使用平台证书私钥做 RSA-SHA256 签名，签名串格式为
     * {@code timestamp\nnonce\nbody\n}，与微信支付回调验签逻辑对称。</p>
     *
     * @param body 回调请求体
     * @return 包含 Wechatpay-Timestamp, Wechatpay-Nonce, Wechatpay-Signature, Wechatpay-Serial 的 Map
     */
    private Map<String, String> buildWeChatSignatureHeaders(String body) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonce = UUID.randomUUID().toString().replace("-", "");

        // 签名串格式：timestamp\nnonce\nbody\n（与 verifyCallbackSignatureWithPlatformCert 对称）
        String signContent = timestamp + "\n" + nonce + "\n" + body + "\n";

        // 使用平台私钥做 RSA-SHA256 签名
        String signature = rsaSign(signContent, callbackKeys.getWechatPlatformPrivateKeyBase64());

        Map<String, String> headers = new HashMap<>();
        headers.put("Wechatpay-Timestamp", timestamp);
        headers.put("Wechatpay-Nonce", nonce);
        headers.put("Wechatpay-Signature", signature);
        headers.put("Wechatpay-Serial", callbackKeys.getWechatPlatformSerialNo());
        return headers;
    }

    // ==================== 加密/签名辅助方法 ====================

    /**
     * AES-256-GCM 加密（与 WeChatPaySignatureUtil.decryptResource 解密对称）。
     *
     * @param plaintext      待加密的明文 JSON
     * @param nonce          GCM nonce
     * @param associatedData GCM 附加认证数据
     * @return Base64 编码的密文（含 GCM tag）
     */
    private String aesGcmEncrypt(String plaintext, String nonce, String associatedData) {
        try {
            byte[] keyBytes = callbackKeys.getWechatApiV3Key().getBytes(StandardCharsets.UTF_8);
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
     * RSA-SHA256 签名（使用 PKCS#8 私钥）。
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

    /**
     * 生成 AES-256-GCM nonce（32 位十六进制字符串）。
     *
     * @return 32 位十六进制 nonce 字符串
     */
    private String generateNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}