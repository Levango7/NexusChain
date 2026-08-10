package org.nexus.gateway.subscription;

import org.nexus.gateway.orchestration.connector.ConnectorPaymentRequest;
import org.nexus.gateway.orchestration.connector.ConnectorPaymentResult;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.routing.RoutingEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 默认扣款执行器（P4-T8 订阅与循环计费引擎）。
 *
 * <p>通过 {@link RoutingEngine} 选择最优扣款通道（复用 P4-T4 AI 路由），
 * 按候选顺序依次尝试扣款，首个成功即返回，全部失败则返回失败结果。
 * 这是订阅扣款与 AI 路由集成的关键桥梁。</p>
 */
@Component
public class DefaultChargeExecutor implements ChargeExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultChargeExecutor.class);

    private final RoutingEngine routingEngine;

    public DefaultChargeExecutor(RoutingEngine routingEngine) {
        this.routingEngine = routingEngine;
    }

    @Override
    public ChargeResult charge(Subscription subscription, BigDecimal amount, String description) {
        if (subscription == null) {
            return ChargeResult.failure("subscription is null", null);
        }
        if (amount == null || amount.signum() <= 0) {
            return ChargeResult.failure("invalid amount: " + amount, null);
        }

        String currency = "NEX";
        long amountLong = amount.longValueExact();

        // 通过 RoutingEngine 选择最优扣款通道（含 AI 路由）
        List<PaymentConnector> connectors = routingEngine.resolve(currency, amountLong, null);
        if (connectors == null || connectors.isEmpty()) {
            log.error("No connector available for subscription charge: sub={}",
                    subscription.getSubscriptionId());
            return ChargeResult.failure("no connector available", null);
        }

        String paymentId = "SUB-" + subscription.getSubscriptionId() + "-" + UUID.randomUUID();
        ConnectorPaymentRequest request = new ConnectorPaymentRequest(
                paymentId, amountLong, currency, description);
        request.setPayerAddress(subscription.getPayerAddress());
        request.setPayeeAddress(subscription.getPayeeAddress());

        // 按候选顺序依次尝试，首个成功即返回
        String lastError = null;
        for (PaymentConnector connector : connectors) {
            try {
                ConnectorPaymentResult result = connector.createPayment(request);
                if (result.isSuccess()) {
                    log.info("Subscription charge succeeded: sub={}, connector={}, txHash={}",
                            subscription.getSubscriptionId(), connector.getId(),
                            result.getTransactionHash());
                    return ChargeResult.success(result.getTransactionHash(), connector.getId());
                }
                lastError = result.getErrorMessage();
                log.warn("Subscription charge failed on connector {}: sub={}, error={}",
                        connector.getId(), subscription.getSubscriptionId(), lastError);
            } catch (Exception e) {
                lastError = e.getMessage();
                log.warn("Subscription charge exception on connector {}: sub={}, error={}",
                        connector.getId(), subscription.getSubscriptionId(), lastError);
            }
        }

        return ChargeResult.failure(lastError != null ? lastError : "all connectors failed",
                connectors.get(0).getId());
    }
}