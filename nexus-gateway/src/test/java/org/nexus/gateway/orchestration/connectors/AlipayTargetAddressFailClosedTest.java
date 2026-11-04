package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.ConnectorConfig;
import org.nexus.gateway.orchestration.connector.ConnectorFactory;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 目标地址闸（2026-11-05，板 20261105-0002-qoder-cn-claim-alipay-target-address）。
 *
 * <p>钉的是**正确行为**，不是现状：修复前 {@code ConnectorFactory.createAlipay} 把地址硬编码成
 * 支付宝公开沙箱域、且 {@code application.yml} / {@code @Value} 两处兜底同一域，
 * 于是"真实 app_id + 真实商户私钥"会被外发到第三方演示域。这些旧行为没有测试为它背书，
 * 只有 {@code AlipayConnectorTest#defaultApiBaseUrl_isEmpty_notSandbox} 一条断言随本次改写。</p>
 */
class AlipayTargetAddressFailClosedTest {

    private static final String PROD_GATEWAY = "https://openapi.alipay.com/gateway.do";
    private static final String SANDBOX_GATEWAY = "https://openapi-sandbox.dl.alipaydev.com/gateway.do";

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = AlipayConnector.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static String readApiBaseUrl(Object target) throws Exception {
        Field f = AlipayConnector.class.getDeclaredField("apiBaseUrl");
        f.setAccessible(true);
        return (String) f.get(target);
    }

    /** 生产模式连接器：凭证齐、sandbox=false，目标地址由用例决定。 */
    private static AlipayConnector realMode(String apiBaseUrl, RestTemplate rt) throws Exception {
        AlipayConnector c = new AlipayConnector();
        String dummyKey = Base64.getEncoder().encodeToString("PKCS8-dummy".getBytes());
        setField(c, "appId", "2021000000000000");
        setField(c, "merchantPrivateKey", dummyKey);
        setField(c, "alipayPublicKey", dummyKey);
        setField(c, "enabled", true);
        setField(c, "sandbox", false);
        setField(c, "apiBaseUrl", apiBaseUrl);
        setField(c, "restTemplate", rt);
        return c;
    }

    private static ConnectorPaymentRequest sampleRequest() {
        return new ConnectorPaymentRequest("pay_target_1", 5000L, "CNY", "target-address gate");
    }

    @Test
    @DisplayName("生产模式 + 空目标地址：四个入口全部拒绝，且一次 HTTP 都不发")
    void blankTarget_refusesAllEntriesAndNeverHitsHttp() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        AlipayConnector c = realMode("", rt);

        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertFalse(created.isSuccess());
        assertEquals(AlipayConnector.TARGET_ADDRESS_REFUSAL, created.getErrorMessage());

        assertFalse(c.closePayment("pay_target_1"));

        ConnectorRefundResult refund = c.refund("pay_target_1", 100L);
        assertFalse(refund.isSuccess());
        assertEquals(AlipayConnector.TARGET_ADDRESS_REFUSAL, refund.getErrorMessage());

        // 查询不新造状态：与本方法既有的"外呼失败"分支同形（回落本地状态）
        assertEquals(PaymentStatus.FAILED, c.queryPayment("pay_target_1"));

        verifyNoInteractions(rt);
    }

    @Test
    @DisplayName("生产模式 + 显式配成公开沙箱域：同样拒绝（配置错误不能变成外发动作）")
    void sandboxTargetInRealMode_refused() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        AlipayConnector c = realMode(SANDBOX_GATEWAY, rt);

        assertFalse(c.targetAddressAllowed());
        assertFalse(c.createPayment(sampleRequest()).isSuccess());
        verifyNoInteractions(rt);
    }

    @Test
    @DisplayName("反向对照：同一沙箱地址在 sandbox=true 下被允许（判据是开关，不是一刀切禁域）")
    void sameSandboxTargetAllowedWhenSandboxEnabled() throws Exception {
        RestTemplate rt = mock(RestTemplate.class);
        AlipayConnector c = realMode(SANDBOX_GATEWAY, rt);
        setField(c, "sandbox", true);
        assertTrue(c.targetAddressAllowed());
        verifyNoInteractions(rt);
    }

    @Test
    @DisplayName("反向对照：生产模式 + 生产网关 ⇒ 放行（证明拒绝分支不是恒真）")
    void productionTargetAllowed() throws Exception {
        AlipayConnector c = realMode(PROD_GATEWAY, mock(RestTemplate.class));
        assertTrue(c.targetAddressAllowed());
    }

    @Test
    @DisplayName("@PostConstruct：空地址 + sandbox=true ⇒ 补沙箱域；空地址 + 生产模式 ⇒ 保持空并 fail-closed")
    void postConstruct_resolvesTargetPerSandboxFlag() throws Exception {
        AlipayConnector sandboxProfile = realMode("", mock(RestTemplate.class));
        setField(sandboxProfile, "sandbox", true);
        sandboxProfile.resolveTargetAddress();
        assertEquals(SANDBOX_GATEWAY, readApiBaseUrl(sandboxProfile));

        AlipayConnector prodProfile = realMode("", mock(RestTemplate.class));
        prodProfile.resolveTargetAddress();
        assertEquals("", readApiBaseUrl(prodProfile));
        assertFalse(prodProfile.targetAddressAllowed());
    }

    @Test
    @DisplayName("ConnectorFactory.createAlipay 透传 config.baseUrl（不再硬编码沙箱域）")
    void factory_passesThroughConfiguredTarget() throws Exception {
        ConnectorConfig config = new ConnectorConfig();
        config.setId("alipay_dyn_1");
        config.setType("alipay");
        config.setDisplayName("dyn");
        config.setAppId("2021000000000000");
        config.setMerchantPrivateKey("NEX_TEST_KEY_ENV");
        config.setAlipayPublicKey("NEX_TEST_PUB_ENV");
        config.setBaseUrl(PROD_GATEWAY);
        config.setActive(true);

        ConnectorFactory factory = new ConnectorFactory(new RestTemplate());
        PaymentConnector connector = factory.create(config);
        assertInstanceOf(AlipayConnector.class, connector);
        assertEquals(PROD_GATEWAY, readApiBaseUrl(connector));

        // 未配 baseUrl 的动态连接器：地址留空 ⇒ 交易期拒绝，绝不静默指向沙箱域
        config.setBaseUrl(null);
        AlipayConnector blank = (AlipayConnector) factory.create(config);
        blank.resolveTargetAddress();
        assertFalse(blank.targetAddressAllowed());
        assertFalse(blank.createPayment(sampleRequest()).isSuccess());
    }
}
