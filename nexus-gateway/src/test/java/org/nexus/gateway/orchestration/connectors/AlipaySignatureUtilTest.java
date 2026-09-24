package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.*;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AlipaySignatureUtil} 单元测试 — 验证 RSA2 签名生成与回调验签。
 *
 * <p>测试中使用 Java 标准库动态生成 RSA 密钥对，模拟商户私钥和支付宝公钥。</p>
 */
class AlipaySignatureUtilTest {

    private static String merchantPrivateKeyBase64;
    private static String alipayPublicKeyBase64;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        // 商户私钥（PKCS#8 格式）
        merchantPrivateKeyBase64 = Base64.getEncoder().encodeToString(
                keyPair.getPrivate().getEncoded());

        // 支付宝公钥（X.509 格式）— 在真实场景中这是支付宝提供的公钥
        // 此处用同一密钥对的公钥模拟
        alipayPublicKeyBase64 = Base64.getEncoder().encodeToString(
                keyPair.getPublic().getEncoded());
    }

    @Test
    @DisplayName("generateSignature：相同参数生成相同签名（确定性）")
    void generateSignature_deterministic() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", "test_app");
        params.put("method", "alipay.trade.query");
        params.put("out_trade_no", "pay_001");

        String sig1 = AlipaySignatureUtil.generateSignature(params, merchantPrivateKeyBase64);
        String sig2 = AlipaySignatureUtil.generateSignature(params, merchantPrivateKeyBase64);

        assertNotNull(sig1);
        assertEquals(sig1, sig2);
    }

    @Test
    @DisplayName("generateSignature：不同参数生成不同签名")
    void generateSignature_differentParams() {
        Map<String, String> params1 = new LinkedHashMap<>();
        params1.put("app_id", "test_app");
        params1.put("method", "alipay.trade.query");
        params1.put("out_trade_no", "pay_001");

        Map<String, String> params2 = new LinkedHashMap<>();
        params2.put("app_id", "test_app");
        params2.put("method", "alipay.trade.query");
        params2.put("out_trade_no", "pay_002");

        String sig1 = AlipaySignatureUtil.generateSignature(params1, merchantPrivateKeyBase64);
        String sig2 = AlipaySignatureUtil.generateSignature(params2, merchantPrivateKeyBase64);

        assertNotEquals(sig1, sig2);
    }

    @Test
    @DisplayName("generateSignature：参数排序不影响签名结果")
    void generateSignature_sortedParams() {
        // 两个 Map 插入顺序不同，但 key 相同
        Map<String, String> params1 = new LinkedHashMap<>();
        params1.put("app_id", "test_app");
        params1.put("method", "alipay.trade.query");
        params1.put("out_trade_no", "pay_001");

        Map<String, String> params2 = new LinkedHashMap<>();
        params2.put("out_trade_no", "pay_001");
        params2.put("method", "alipay.trade.query");
        params2.put("app_id", "test_app");

        String sig1 = AlipaySignatureUtil.generateSignature(params1, merchantPrivateKeyBase64);
        String sig2 = AlipaySignatureUtil.generateSignature(params2, merchantPrivateKeyBase64);

        assertEquals(sig1, sig2);
    }

    @Test
    @DisplayName("generateSignature：空值参数不参与签名")
    void generateSignature_emptyValueExcluded() {
        Map<String, String> paramsWithEmpty = new LinkedHashMap<>();
        paramsWithEmpty.put("app_id", "test_app");
        paramsWithEmpty.put("method", "alipay.trade.query");
        paramsWithEmpty.put("out_trade_no", "pay_001");
        paramsWithEmpty.put("empty_param", "");

        Map<String, String> paramsWithoutEmpty = new LinkedHashMap<>();
        paramsWithoutEmpty.put("app_id", "test_app");
        paramsWithoutEmpty.put("method", "alipay.trade.query");
        paramsWithoutEmpty.put("out_trade_no", "pay_001");

        String sig1 = AlipaySignatureUtil.generateSignature(paramsWithEmpty, merchantPrivateKeyBase64);
        String sig2 = AlipaySignatureUtil.generateSignature(paramsWithoutEmpty, merchantPrivateKeyBase64);

        assertEquals(sig1, sig2);
    }

    @Test
    @DisplayName("verifyCallbackSignature：正确签名验签通过")
    void verifyCallbackSignature_valid() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", "test_app");
        params.put("method", "alipay.trade.query");
        params.put("out_trade_no", "pay_001");
        params.put("trade_status", "TRADE_SUCCESS");

        // 生成签名
        String sign = AlipaySignatureUtil.generateSignature(params, merchantPrivateKeyBase64);
        params.put("sign", sign);
        params.put("sign_type", "RSA2");

        // 验签（用同一密钥对的公钥模拟支付宝公钥）
        boolean verified = AlipaySignatureUtil.verifyCallbackSignature(params, alipayPublicKeyBase64);
        assertTrue(verified);
    }

    @Test
    @DisplayName("verifyCallbackSignature：篡改参数后验签失败")
    void verifyCallbackSignature_tamperedParams() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", "test_app");
        params.put("method", "alipay.trade.query");
        params.put("out_trade_no", "pay_001");
        params.put("trade_status", "TRADE_SUCCESS");

        // 生成签名
        String sign = AlipaySignatureUtil.generateSignature(params, merchantPrivateKeyBase64);
        params.put("sign", sign);
        params.put("sign_type", "RSA2");

        // 篡改参数
        params.put("trade_status", "TRADE_CLOSED");

        boolean verified = AlipaySignatureUtil.verifyCallbackSignature(params, alipayPublicKeyBase64);
        assertFalse(verified);
    }

    @Test
    @DisplayName("verifyCallbackSignature：缺少 sign 参数验签失败")
    void verifyCallbackSignature_noSign() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", "test_app");
        params.put("out_trade_no", "pay_001");

        boolean verified = AlipaySignatureUtil.verifyCallbackSignature(params, alipayPublicKeyBase64);
        assertFalse(verified);
    }

    @Test
    @DisplayName("verifyCallbackSignature：sign 和 sign_type 不参与签名内容")
    void verifyCallbackSignature_signExcludedFromContent() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", "test_app");
        params.put("out_trade_no", "pay_001");
        params.put("trade_status", "TRADE_SUCCESS");

        String sign = AlipaySignatureUtil.generateSignature(params, merchantPrivateKeyBase64);
        params.put("sign", sign);
        params.put("sign_type", "RSA2");

        // 验签时 sign 和 sign_type 被排除在签名内容之外，验签应通过
        boolean verified = AlipaySignatureUtil.verifyCallbackSignature(params, alipayPublicKeyBase64);
        assertTrue(verified);
    }

    @Test
    @DisplayName("generateSignature：签名结果为 Base64 格式")
    void generateSignature_base64Format() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", "test_app");

        String signature = AlipaySignatureUtil.generateSignature(params, merchantPrivateKeyBase64);
        assertNotNull(signature);
        // Base64 字符串只包含 A-Z, a-z, 0-9, +, /, =
        assertTrue(signature.matches("^[A-Za-z0-9+/=]+$"));
    }
}