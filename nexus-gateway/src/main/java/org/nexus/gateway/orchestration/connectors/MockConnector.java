package org.nexus.gateway.orchestration.connectors;

import org.nexus.gateway.orchestration.connector.*;
import org.nexus.gateway.orchestration.settlement.FinalityPolicy;
import org.nexus.gateway.orchestration.settlement.MockFinalityPolicy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock connector for sandbox/testing. Always succeeds with configurable delay.
 *
 * <p><b>P0 修复（2026-10-09）：生产闸门。</b>此前该 Bean 无任何 Profile 保护、
 * {@code isActive()} 恒 true，且 {@code RoutingEngine} 的默认兜底规则把
 * {@code "mock"} 列在所有币种的首选位——生产环境只要某币种没有更高优先级规则，
 * 就会命中本连接器：返回 {@code mock_tx_*} 假哈希、状态直接 SUCCEEDED。
 * 即"支付从未上链却报告成功"。</p>
 *
 * <p>现以 {@code @Profile("!prod")} 把本连接器从生产上下文整体移除：
 * 生产下 {@code ConnectorRegistry} 中不存在 "mock"，路由规则解析时
 * {@code registry.get("mock")} 返回空 → 候选链自动跳过 mock（fail-closed，
 * 而非 fail-open）。staging 保留（k6 容量测试以 mock 为压测目标），
 * dev/sandbox/test 保留（本地与 CI 的 E2E 依赖）。</p>
 */
@Component
@Profile("!prod")
public class MockConnector implements PaymentConnector {

    private final Map<String, PaymentStatus> payments = new ConcurrentHashMap<>();

    @Override
    public String getId() { return "mock"; }

    @Override
    public String getType() { return "mock"; }

    @Override
    public String getDisplayName() { return "Mock Connector (Sandbox)"; }

    @Override
    public boolean isActive() { return true; }

    @Override
    public ConnectorPaymentResult createPayment(ConnectorPaymentRequest request) {
        String connectorId = "mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        payments.put(connectorId, PaymentStatus.SUCCEEDED);
        return ConnectorPaymentResult.ok(connectorId, PaymentStatus.SUCCEEDED, "mock_tx_" + connectorId);
    }

    @Override
    public PaymentStatus queryPayment(String connectorPaymentId) {
        return payments.getOrDefault(connectorPaymentId, PaymentStatus.FAILED);
    }

    @Override
    public ConnectorRefundResult refund(String connectorPaymentId, long amount) {
        if (payments.containsKey(connectorPaymentId)) {
            payments.put(connectorPaymentId, PaymentStatus.REFUNDED);
            return ConnectorRefundResult.ok("refund_" + connectorPaymentId);
        }
        return ConnectorRefundResult.fail("Payment not found");
    }

    @Override
    public ConnectorHealth healthCheck() {
        return ConnectorHealth.up(getId(), 1);
    }

    @Override
    public Set<String> supportedCurrencies() { return Set.of(); }

    @Override
    public FinalityPolicy getFinalityPolicy() {
        return new MockFinalityPolicy();
    }

    @Override
    public int feeBasisPoints() { return 0; }
}