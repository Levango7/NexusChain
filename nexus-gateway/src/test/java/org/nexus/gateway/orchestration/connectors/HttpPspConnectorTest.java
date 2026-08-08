package org.nexus.gateway.orchestration.connectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.ConnectorHealth;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.ConnectorRefundResult;
import org.nexus.gateway.orchestration.connector.PaymentStatus;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HttpPspConnector} 单元测试：覆盖通用 HTTP PSP 连接器的支付创建、查询、退款、
 * 健康检查以及元数据访问器。该连接器不依赖外部 RPC，状态保存在内存 Map 中。
 */
class HttpPspConnectorTest {

    private HttpPspConnector newConnector() {
        return new HttpPspConnector("psp1", "My PSP", "https://api.psp.example",
                "key-123", 150, Set.of("USD", "EUR", "NEX"));
    }

    private ConnectorPaymentRequest sampleRequest() {
        return new ConnectorPaymentRequest("pay_1", 10000L, "USD", "test payment");
    }

    @Test
    @DisplayName("metadata: id/type/displayName/active/feeBps/currencies")
    void metadata() {
        HttpPspConnector c = newConnector();
        assertEquals("psp1", c.getId());
        assertEquals("http_psp", c.getType());
        assertEquals("My PSP", c.getDisplayName());
        assertTrue(c.isActive());
        assertEquals(150, c.feeBasisPoints());
        assertTrue(c.supportedCurrencies().contains("USD"));
        assertEquals(3, c.supportedCurrencies().size());
    }

    @Test
    @DisplayName("setActive(false) 后 isActive 返回 false")
    void setActive_false() {
        HttpPspConnector c = newConnector();
        c.setActive(false);
        assertFalse(c.isActive());
    }

    @Test
    @DisplayName("createPayment: 成功 -> PROCESSING + connectorPaymentId 以 id 为前缀")
    void createPayment_success() {
        HttpPspConnector c = newConnector();
        ConnectorPaymentResult r = c.createPayment(sampleRequest());
        assertTrue(r.isSuccess());
        assertEquals(PaymentStatus.PROCESSING, r.getStatus());
        assertNotNull(r.getConnectorPaymentId());
        assertTrue(r.getConnectorPaymentId().startsWith("psp1_"));
    }

    @Test
    @DisplayName("queryPayment: 已创建 -> PROCESSING；未知 -> FAILED")
    void queryPayment_knownAndUnknown() {
        HttpPspConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        assertEquals(PaymentStatus.PROCESSING, c.queryPayment(created.getConnectorPaymentId()));
        assertEquals(PaymentStatus.FAILED, c.queryPayment("non-existent"));
    }

    @Test
    @DisplayName("refund: 已知 payment -> ok + 状态变 REFUNDED")
    void refund_knownPayment() {
        HttpPspConnector c = newConnector();
        ConnectorPaymentResult created = c.createPayment(sampleRequest());
        ConnectorRefundResult r = c.refund(created.getConnectorPaymentId(), 5000L);
        assertTrue(r.isSuccess());
        assertNotNull(r.getRefundId());
        // 退款后状态变为 REFUNDED
        assertEquals(PaymentStatus.REFUNDED, c.queryPayment(created.getConnectorPaymentId()));
    }

    @Test
    @DisplayName("refund: 未知 payment -> fail")
    void refund_unknownPayment() {
        HttpPspConnector c = newConnector();
        ConnectorRefundResult r = c.refund("unknown-payout", 100L);
        assertFalse(r.isSuccess());
        assertNotNull(r.getErrorMessage());
    }

    @Test
    @DisplayName("healthCheck: 始终 up（无外部调用）")
    void healthCheck_up() {
        HttpPspConnector c = newConnector();
        ConnectorHealth h = c.healthCheck();
        assertTrue(h.isHealthy());
        assertEquals("psp1", h.getConnectorId());
        assertTrue(h.getLatencyMs() >= 0);
    }
}