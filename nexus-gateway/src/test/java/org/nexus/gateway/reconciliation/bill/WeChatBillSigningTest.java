package org.nexus.gateway.reconciliation.bill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 微信账单下载 Authorization 头签名回归测试（P0 修复 2026-10-09）。
 *
 * <p>背景：{@code buildAuthHeaders} 此前把 signature 硬编码为 {@code "placeholder"} ——
 * 真实模式下必被微信拒绝（异常被 catch 吞成空对账单），"日终对账"永远拿不到真实账单。
 * 本测试钉住：Authorization 头携带的是**可用商户公钥验回的 RSA-SHA256 真签名**，
 * 且签名串严格为 V3 规范 {@code method\nurl\ntimestamp\nnonce\nbody\n}。</p>
 */
class WeChatBillSigningTest {

    private static Field field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Test
    @DisplayName("buildAuthHeaders：signature 为真 RSA 签名且可被商户公钥验回（非 placeholder）")
    void authHeaderCarriesVerifiableRsaSignature() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair keyPair = gen.generateKeyPair();
        String privateKeyBase64 =
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

        WeChatBillDownloadClient client = new WeChatBillDownloadClient(new RestTemplate());
        field(client, "mchId").set(client, "1900000001");
        field(client, "certSerialNo").set(client, "SERIAL123");
        field(client, "merchantPrivateKey").set(client, privateKeyBase64);
        field(client, "sandbox").set(client, false);
        field(client, "enabled").set(client, true);

        Method buildAuthHeaders =
                WeChatBillDownloadClient.class.getDeclaredMethod("buildAuthHeaders", String.class, String.class);
        buildAuthHeaders.setAccessible(true);
        String urlPath = "/v3/bill/tradebill?bill_date=2026-10-08&bill_type=ALL";
        HttpHeaders headers = (HttpHeaders) buildAuthHeaders.invoke(client, "GET", urlPath);

        String authorization = headers.getFirst("Authorization");
        assertNotNull(authorization, "必须设置 Authorization 头");
        assertTrue(authorization.startsWith("WECHATPAY2-SHA256-RSA2048 "),
                "签名协议头前缀应为 WECHATPAY2-SHA256-RSA2048，实际=" + authorization);

        Matcher m = Pattern.compile(
                "mchid=\"([^\"]+)\",serial_no=\"([^\"]+)\",timestamp=\"([^\"]+)\","
                        + "nonce_str=\"([^\"]+)\",signature=\"([^\"]+)\"").matcher(authorization);
        assertTrue(m.find(), "Authorization 头结构应含 5 字段，实际=" + authorization);
        assertEquals("1900000001", m.group(1));
        assertEquals("SERIAL123", m.group(2));
        String timestamp = m.group(3);
        String nonce = m.group(4);
        String signatureB64 = m.group(5);

        // P0 回归门禁：不得再是占位符
        assertNotEquals("placeholder", signatureB64, "signature 不得是 placeholder");

        // 用同一规范签名串 + 商户公钥验回（证明是真实签名，而非任意串）
        String signContent = "GET\n" + urlPath + "\n" + timestamp + "\n" + nonce + "\n\n";
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(keyPair.getPublic());
        verifier.update(signContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(verifier.verify(Base64.getDecoder().decode(signatureB64)),
                "签名应能被商户公钥验回（V3 规范签名串）");
    }

    @Test
    @DisplayName("buildAuthHeaders：私钥缺失 fail-closed（抛异常，不发送未签名请求）")
    void authHeaderFailsClosedWithoutPrivateKey() throws Exception {
        WeChatBillDownloadClient client = new WeChatBillDownloadClient(new RestTemplate());
        field(client, "mchId").set(client, "1900000001");
        field(client, "certSerialNo").set(client, "SERIAL123");
        // merchantPrivateKey 留空 → 签名必失败
        Method buildAuthHeaders =
                WeChatBillDownloadClient.class.getDeclaredMethod("buildAuthHeaders", String.class, String.class);
        buildAuthHeaders.setAccessible(true);

        java.lang.reflect.InvocationTargetException ex = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> buildAuthHeaders.invoke(client, "GET", "/v3/bill/tradebill"));
        assertTrue(ex.getCause() instanceof IllegalStateException,
                "根因应为 IllegalStateException（fail-closed），实际=" + ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("拒绝发送未签名请求"),
                "错误信息应说明拒发原因，实际=" + ex.getCause().getMessage());
    }
}
