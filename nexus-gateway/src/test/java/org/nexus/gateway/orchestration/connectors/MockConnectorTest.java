package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link MockConnector} 单元测试：沙箱 Mock 连接器始终成功，覆盖支付创建、查询、
 * 退款、健康检查与元数据。
 */
class MockConnectorTest {

    private ConnectorPaymentRequest sampleRequest() {
        return new ConnectorPaymentRequest("pay_1", 1000L, "NEX", "mock test");
    }

    @Test
    @DisplayName("metadata: id/type/displayName/active/feeBps/currencies")
    void metadata() {
        MockConnector c = new MockConnector();
        assertEquals("mock", c.getId());
        assertEquals("mock", c.getType());
        assertEquals("Mock Connector (Sandbox)", c.getDisplayName());
        assertTrue(c.isActive());
        assertEquals(0, c.feeBasisPoints());
        assertTrue(c.supportedCurrencies().isEmpty());
    }

    @Test
    @DisplayName("createPayment: 始终 SUCCEEDED + 返回 mock_tx 交易哈希")
    void createPayment_success() {
        MockConnector c = new MockConnector();
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.SUCCEEDED, r.getStatus());
        assertNotNull(r.getConnectorPaymentId());
        assertTrue(r.getConnectorPaymentId().startsWith("mock_"));
        assertNotNull(r.getTransactionHash());
        assertTrue(r.getTransactionHash().startsWith("mock_tx_"));
    }

    @Test
    @DisplayName("queryPayment: 已创建 -> SUCCEEDED；未知 -> FAILED")
    void queryPayment_knownAndUnknown() {
        MockConnector c = new MockConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.SUCCEEDED, c.queryPayment(created.getConnectorPaymentId()));
        assertEquals(PaymentStatus.FAILED, c.queryPayment("unknown"));
    }

    @Test
    @DisplayName("refund: 已知 payment -> ok + 状态变 REFUNDED")
    void refund_knownPayment() {
        MockConnector c = new MockConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        ConnectorRefundResult r = c.refund(created.getConnectorPaymentId(), 500L);
        assertTrue(r.isSuccess());
        assertNotNull(r.getRefundId());
        assertEquals(PaymentStatus.REFUNDED, c.queryPayment(created.getConnectorPaymentId()));
    }

    @Test
    @DisplayName("refund: 未知 payment -> fail")
    void refund_unknownPayment() {
        MockConnector c = new MockConnector();
        ConnectorRefundResult r = c.refund("unknown", 100L);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("healthCheck: 始终 up")
    void healthCheck_up() {
        MockConnector c = new MockConnector();
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertEquals("mock", h.getConnectorId());
        assertEquals(1L, h.getLatencyMs());
    }

    @Test
    @DisplayName("生产闸门：@Profile(\"!prod\") — prod 上下文不得注册 mock 连接器")
    void profileExcludesProd() {
        // P0 修复回归门禁（2026-10-09）：mock 连接器恒 SUCCEEDED 并返回 mock_tx_* 假哈希。
        // 若它在 prod 注册，路由兜底规则会命中它 → "支付从未上链却报告成功"。
        // 本断言钉住 @Profile 排除 prod；一旦被误删（如重构注解），测试立刻红。
        org.springframework.context.annotation.Profile profile =
                MockConnector.class.getAnnotation(org.springframework.context.annotation.Profile.class);
        assertNotNull(profile, "MockConnector 必须带 @Profile 注解（生产闸门）");
        assertTrue(java.util.Arrays.asList(profile.value()).contains("!prod"),
                "MockConnector 的 @Profile 必须排除 prod，实际=" + java.util.Arrays.toString(profile.value()));
    }
}