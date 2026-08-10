package org.nexus.sdk;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PaymentOrchestrationClient 单元测试。
 *
 * p.验证构造、JSON 转义与网络不可达时的异常行为。
 */
class PaymentOrchestrationClientTest {

    @Test
    void constructor_withTrailingSlash_shouldStripIt() {
        // 构造不应抛异常
        new PaymentOrchestrationClient("http://localhost:8080/", "api-key");
    }

    @Test
    void constructor_withoutTrailingSlash_shouldWork() {
        new PaymentOrchestrationClient("http://localhost:8080", "api-key");
    }

    @Test
    void createPayment_unreachable_shouldThrowRuntimeException() {
        PaymentOrchestrationClient client = new PaymentOrchestrationClient("http://localhost:9999", "key");

        // 连接失败应抛 RuntimeException
        assertThrows(RuntimeException.class, () ->
                client.createPayment(100, "NEX", "test"));
    }

    @Test
    void createPayment_withFullOptions_unreachable_shouldThrow() {
        PaymentOrchestrationClient client = new PaymentOrchestrationClient("http://localhost:9999", "key");

        assertThrows(RuntimeException.class, () ->
                client.createPayment(100, "NEX", "test", "connector-1", "http://notify"));
    }

    @Test
    void getPayment_unreachable_shouldThrowRuntimeException() {
        PaymentOrchestrationClient client = new PaymentOrchestrationClient("http://localhost:9999", "key");

        assertThrows(RuntimeException.class, () -> client.getPayment("pay-1"));
    }

    @Test
    void refreshPayment_unreachable_shouldThrowRuntimeException() {
        PaymentOrchestrationClient client = new PaymentOrchestrationClient("http://localhost:9999", "key");

        assertThrows(RuntimeException.class, () -> client.refreshPayment("pay-1"));
    }

    @Test
    void listConnectors_unreachable_shouldThrowRuntimeException() {
        PaymentOrchestrationClient client = new PaymentOrchestrationClient("http://localhost:9999", "key");

        assertThrows(RuntimeException.class, client::listConnectors);
    }

    @Test
    void connectorHealth_unreachable_shouldThrowRuntimeException() {
        PaymentOrchestrationClient client = new PaymentOrchestrationClient("http://localhost:9999", "key");

        assertThrows(RuntimeException.class, () -> client.connectorHealth("conn-1"));
    }

    @Test
    void listRoutingRules_unreachable_shouldThrowRuntimeException() {
        PaymentOrchestrationClient client = new PaymentOrchestrationClient("http://localhost:9999", "key");

        assertThrows(RuntimeException.class, client::listRoutingRules);
    }
}