package org.nexus.gateway.sandbox.callback;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connectors.AlipaySignatureUtil;
import org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 沙箱回调模拟器的**往返契约**测试（板 20261105-0002-qoder-cn-claim-callback-simulator-tests）。
 *
 * <p>钉的是"模拟器产出的报文必须能被接收侧验签/解密"这一对偶关系，而不是报文长相。
 * 此前那 355 行实现零自身单测（test 树里只有两个同名 mock 替身，容易被误判成已覆盖），
 * 于是它与 {@code WeChatPaySignatureUtil} / {@code AlipaySignatureUtil} 的形状一旦漂移，
 * 沙箱链路单边绿、真实联调时才炸。</p>
 */
class CallbackSimulatorServiceTest {

    private static final String API_V3_KEY = "0123456789abcdef0123456789abcdef"; // 32 字节
    private static final String SERIAL_NO = "PLATFORM-SERIAL-001";
    private static final String APP_ID = "2021000000000000";
    private static final String BASE_URL = "http://localhost:8080";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private KeyPair wechatPair;
    private KeyPair alipayPair;

    @BeforeEach
    void generateKeys() throws NoSuchAlgorithmException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        wechatPair = gen.generateKeyPair();
        alipayPair = gen.generateKeyPair();
    }

    private String publicB64(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    private SandboxCallbackKeys stubKeys() throws Exception {
        SandboxCallbackKeys keys = new SandboxCallbackKeys();
        set(keys, "wechatPlatformKeyPair", wechatPair);
        set(keys, "alipayMerchantKeyPair", alipayPair);
        set(keys, "wechatPlatformSerialNo", SERIAL_NO);
        set(keys, "wechatApiV3Key", API_V3_KEY);
        set(keys, "alipayAppId", APP_ID);
        return keys;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** 捕获 POST 出去的原报文：url / body / form / headers。 */
    private static final class Captured {
        String url;
        String body;
        MultiValueMap<String, String> form;
        final Map<String, String> headers = new HashMap<>();
    }

    private Captured stubCapture(RestTemplate rt) {
        Captured cap = new Captured();
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(String.class)))
                .thenAnswer(inv -> {
                    cap.url = inv.getArgument(0);
                    HttpEntity<?> entity = inv.getArgument(2);
                    Object body = entity.getBody();
                    if (body instanceof MultiValueMap) {
                        @SuppressWarnings("unchecked")
                        MultiValueMap<String, String> form = (MultiValueMap<String, String>) body;
                        cap.form = form;
                    } else {
                        cap.body = String.valueOf(body);
                    }
                    entity.getHeaders().forEach((k, v) -> cap.headers.put(k, v.isEmpty() ? "" : v.get(0)));
                    return new ResponseEntity<>("{\"code\":\"SUCCESS\"}", HttpStatus.OK);
                });
        return cap;
    }

    // ==================== 微信：签名 + 加密 往返 ====================

    @Test
    @DisplayName("微信回调：产出的报文能被接收侧验签并解密出原始订单号/状态")
    void weChatCallback_roundTripsThroughReceiverVerifier() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        CallbackSimulatorService svc = new CallbackSimulatorService(stubKeys(), rt);
        Captured cap = stubCapture(rt);

        Map<String, Object> result = svc.simulateWeChatCallback("trade_rt_1", "SUCCESS", BASE_URL);

        assertEquals(BASE_URL + "/api/v1/callbacks/wechat", cap.url);
        assertNotNull(cap.body);
        assertEquals(200, result.get("responseStatus"));

        // 1) 验签：与接收侧走同一个工具方法
        String signature = cap.headers.get("Wechatpay-Signature");
        assertNotNull(signature);
        assertTrue(WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                        cap.headers.get("Wechatpay-Timestamp"), cap.headers.get("Wechatpay-Nonce"),
                        cap.body, signature, publicB64(wechatPair)),
                "模拟器产出的签名必须能被平台公钥验过，否则沙箱链路只在单边绿");

        // 2) 头部四要素齐备
        assertNotNull(cap.headers.get("Wechatpay-Timestamp"));
        assertNotNull(cap.headers.get("Wechatpay-Nonce"));
        assertEquals(SERIAL_NO, cap.headers.get("Wechatpay-Serial"));

        // 3) 解密 resource 并核对业务字段
        JsonNode resource = objectMapper.readTree(cap.body).path("resource");
        assertEquals("AEAD_AES_256_GCM", resource.path("algorithm").asText());
        assertEquals("transaction", resource.path("original_type").asText());
        String plaintext = WeChatPaySignatureUtil.decryptResource(
                resource.path("ciphertext").asText(), resource.path("nonce").asText(),
                resource.path("associated_data").asText(), API_V3_KEY);
        JsonNode tx = objectMapper.readTree(plaintext);
        assertEquals("trade_rt_1", tx.path("out_trade_no").asText());
        assertEquals("SUCCESS", tx.path("trade_state").asText());
        assertEquals("NATIVE", tx.path("trade_type").asText());
    }

    @Test
    @DisplayName("反向对照：body 被改一处 ⇒ 接收侧验签必须失败（证明断言有牙）")
    void weChatCallback_tamperedBody_failsVerification() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        CallbackSimulatorService svc = new CallbackSimulatorService(stubKeys(), rt);
        Captured cap = stubCapture(rt);

        svc.simulateWeChatCallback("trade_tamper_1", "SUCCESS", BASE_URL);
        String tampered = cap.body.replace("支付成功", "支付失败");
        assertFalse(tampered.equals(cap.body), "篡改必须真的改变 body，否则这条对照是空断言");

        assertFalse(WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                cap.headers.get("Wechatpay-Timestamp"), cap.headers.get("Wechatpay-Nonce"),
                tampered, cap.headers.get("Wechatpay-Signature"), publicB64(wechatPair)));
    }

    @Test
    @DisplayName("反向对照：APIv3 密钥不一致 ⇒ 解密必须抛错（GCM tag 挡住）")
    void weChatCallback_wrongKey_cannotDecrypt() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        CallbackSimulatorService svc = new CallbackSimulatorService(stubKeys(), rt);
        Captured cap = stubCapture(rt);

        svc.simulateWeChatCallback("trade_key_1", "SUCCESS", BASE_URL);
        JsonNode resource = objectMapper.readTree(cap.body).path("resource");
        assertThrows(RuntimeException.class, () -> WeChatPaySignatureUtil.decryptResource(
                resource.path("ciphertext").asText(), resource.path("nonce").asText(),
                resource.path("associated_data").asText(), "ffffffffffffffffffffffffffffffff"));
    }

    // ==================== 支付宝：RSA2 表单签名往返 ====================

    @Test
    @DisplayName("支付宝回调：form 参数能被商户公钥验签，且 sign_type/路径形状固定")
    void alipayCallback_roundTripsThroughVerifier() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        CallbackSimulatorService svc = new CallbackSimulatorService(stubKeys(), rt);
        Captured cap = stubCapture(rt);

        Map<String, Object> result = svc.simulateAlipayCallback("trade_ap_1", "TRADE_SUCCESS", BASE_URL);

        assertEquals(BASE_URL + "/api/v1/callbacks/alipay", cap.url);
        assertNotNull(cap.form);
        assertEquals("alipay", result.get("provider"));

        Map<String, String> params = new LinkedHashMap<>();
        cap.form.forEach((k, v) -> params.put(k, v.get(0)));
        assertEquals("RSA2", params.get("sign_type"));
        assertEquals(APP_ID, params.get("app_id"));
        assertEquals("trade_ap_1", params.get("out_trade_no"));
        assertEquals("TRADE_SUCCESS", params.get("trade_status"));
        assertNotNull(params.get("notify_id"));
        assertNotNull(params.get("trade_no"));

        assertTrue(AlipaySignatureUtil.verifyCallbackSignature(params, publicB64(alipayPair)),
                "模拟器签的回调必须能被接收侧（同一套 AlipaySignatureUtil）验过");
    }

    @Test
    @DisplayName("反向对照：改动 trade_status ⇒ 验签失败")
    void alipayCallback_tamperedStatus_failsVerification() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        CallbackSimulatorService svc = new CallbackSimulatorService(stubKeys(), rt);
        Captured cap = stubCapture(rt);

        svc.simulateAlipayCallback("trade_ap_2", "TRADE_SUCCESS", BASE_URL);
        Map<String, String> params = new LinkedHashMap<>();
        cap.form.forEach((k, v) -> params.put(k, v.get(0)));
        params.put("trade_status", "TRADE_CLOSED");

        assertFalse(AlipaySignatureUtil.verifyCallbackSignature(params, publicB64(alipayPair)));
    }

    // ==================== 返回 Map 的形状（调用方会读） ====================

    @Test
    @DisplayName("返回 Map 暴露可复现的原文：微信给 requestBody/requestHeaders，支付宝给 requestParams")
    void resultMaps_exposeKeysCallersRead() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        CallbackSimulatorService svc = new CallbackSimulatorService(stubKeys(), rt);
        stubCapture(rt);

        Map<String, Object> wechat = svc.simulateWeChatCallback("trade_map_1", "SUCCESS", BASE_URL);
        assertEquals("wechat", wechat.get("provider"));
        assertNotNull(wechat.get("requestBody"));
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) wechat.get("requestHeaders");
        assertNotNull(headers.get("Wechatpay-Signature"));

        Map<String, Object> alipay = svc.simulateAlipayCallback("trade_map_2", "TRADE_SUCCESS", BASE_URL);
        assertEquals("alipay", alipay.get("provider"));
        @SuppressWarnings("unchecked")
        Map<String, String> params = (Map<String, String>) alipay.get("requestParams");
        assertNotNull(params.get("sign"));
        assertEquals("TRADE_SUCCESS", params.get("trade_status"));
    }
}
