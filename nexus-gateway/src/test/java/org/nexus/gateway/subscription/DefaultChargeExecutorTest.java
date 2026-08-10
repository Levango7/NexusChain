package org.nexus.gateway.subscription;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.connector.PaymentStatus;
import org.nexus.gateway.orchestration.routing.RoutingEngine;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link DefaultChargeExecutor} 单元测试（P4-T8）。
 *
 * <p>验证订阅扣款与 AI 路由集成：RoutingEngine 选择候选通道，
 * 按顺序尝试扣款，首个成功返回，全部失败返回失败结果。</p>
 */
@ExtendWith(MockitoExtension.class)
class DefaultChargeExecutorTest {

    @Mock private RoutingEngine routingEngine;

    private DefaultChargeExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DefaultChargeExecutor(routingEngine);
    }

    private Subscription newSubscription() {
        Subscription s = new Subscription();
        s.setSubscriptionId("SUB-001");
        s.setPayerAddress("0xPayer");
        s.setPayeeAddress("0xPayee");
        s.setCurrentPeriodStart(LocalDateTime.now());
        s.setCurrentPeriodEnd(LocalDateTime.now().plusDays(30));
        return s;
    }

    private PaymentConnector mockConnector(String id, boolean success) {
        PaymentConnector c = mock(PaymentConnector.class);
        when(c.getId()).thenReturn(id);
        ConnectorPaymentResult result;
        if (success) {
            result = ConnectorPaymentResult.ok("pay-" + id, PaymentStatus.SUCCEEDED, "0xTx-" + id);
        } else {
            result = ConnectorPaymentResult.fail("connector " + id + " error");
        }
        when(c.createPayment(any(ConnectorPaymentRequest.class))).thenReturn(result);
        return c;
    }

    @Test
    @DisplayName("charge: 首个候选成功 -> 返回成功")
    void charge_firstConnectorSuccess() {
        PaymentConnector chain = mockConnector("chain", true);
        when(routingEngine.resolve(eq("NEX"), anyLong(), isNull()))
                .thenReturn(List.of(chain));

        ChargeResult result = executor.charge(newSubscription(), new BigDecimal("1000"), "test");

        assertTrue(result.isSuccess());
        assertEquals("0xTx-chain", result.getTransactionHash());
        assertEquals("chain", result.getConnectorId());
    }

    @Test
    @DisplayName("charge: 首个失败，第二个成功 -> failover 成功")
    void charge_failoverToSecondConnector() {
        PaymentConnector chain = mockConnector("chain", false);
        PaymentConnector consortium = mockConnector("consortium", true);
        when(routingEngine.resolve(eq("NEX"), anyLong(), isNull()))
                .thenReturn(List.of(chain, consortium));

        ChargeResult result = executor.charge(newSubscription(), new BigDecimal("1000"), "test");

        assertTrue(result.isSuccess());
        assertEquals("0xTx-consortium", result.getTransactionHash());
        assertEquals("consortium", result.getConnectorId());
    }

    @Test
    @DisplayName("charge: 全部候选失败 -> 返回失败")
    void charge_allConnectorsFail() {
        PaymentConnector chain = mockConnector("chain", false);
        PaymentConnector consortium = mockConnector("consortium", false);
        when(routingEngine.resolve(eq("NEX"), anyLong(), isNull()))
                .thenReturn(List.of(chain, consortium));

        ChargeResult result = executor.charge(newSubscription(), new BigDecimal("1000"), "test");

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }

    @Test
    @DisplayName("charge: 候选列表为空 -> 返回失败")
    void charge_noConnectorsAvailable() {
        when(routingEngine.resolve(eq("NEX"), anyLong(), isNull()))
                .thenReturn(List.of());

        ChargeResult result = executor.charge(newSubscription(), new BigDecimal("1000"), "test");

        assertFalse(result.isSuccess());
        assertEquals("no connector available", result.getErrorMessage());
    }

    @Test
    @DisplayName("charge: connector 抛异常 -> failover 到下一个")
    void charge_connectorException_failover() {
        PaymentConnector chain = mock(PaymentConnector.class);
        when(chain.getId()).thenReturn("chain");
        when(chain.createPayment(any())).thenThrow(new RuntimeException("network error"));

        PaymentConnector consortium = mockConnector("consortium", true);
        when(routingEngine.resolve(eq("NEX"), anyLong(), isNull()))
                .thenReturn(List.of(chain, consortium));

        ChargeResult result = executor.charge(newSubscription(), new BigDecimal("1000"), "test");

        assertTrue(result.isSuccess());
        assertEquals("consortium", result.getConnectorId());
    }

    @Test
    @DisplayName("charge: null 订阅 -> 返回失败")
    void charge_nullSubscription() {
        ChargeResult result = executor.charge(null, new BigDecimal("1000"), "test");
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("charge: 非正金额 -> 返回失败")
    void charge_nonPositiveAmount() {
        ChargeResult r1 = executor.charge(newSubscription(), BigDecimal.ZERO, "test");
        assertFalse(r1.isSuccess());

        ChargeResult r2 = executor.charge(newSubscription(), new BigDecimal("-100"), "test");
        assertFalse(r2.isSuccess());
    }
}