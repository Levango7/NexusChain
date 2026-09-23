package org.nexus.gateway.orchestration.connector;

import java.util.Map;

/**
 * Payment Connector SPI - the core abstraction for pluggable payment channels.
 * Each connector represents one payment channel (chain, PSP, mock, etc.)
 * Implement this interface to add a new payment channel to the orchestration engine.
 */
public interface PaymentConnector {

    /** Unique connector identifier, e.g. "chain", "stripe", "mock" */
    String getId();

    /** Connector type for categorization: "chain", "http_psp", "mock" */
    String getType();

    /** Human-readable display name */
    String getDisplayName();

    /** Whether this connector is currently active and accepting payments */
    boolean isActive();

    /**
     * Create a payment through this connector.
     * @param request the payment request details
     * @return result containing connector-specific payment ID and status
     */
    ConnectorPaymentResult createPayment(ConnectorPaymentRequest request);

    /**
     * Query the current status of a payment.
     * @param connectorPaymentId the ID returned by createPayment
     * @return current status
     */
    PaymentStatus queryPayment(String connectorPaymentId);

    /**
     * Refund a previously succeeded payment.
     * @param connectorPaymentId the original payment ID
     * @param amount amount to refund (in smallest unit)
     * @return refund result
     */
    ConnectorRefundResult refund(String connectorPaymentId, long amount);

    /**
     * Health check - is this connector reachable and functional?
     * @return health status
     */
    ConnectorHealth healthCheck();

    /**
     * Supported currencies. Empty = all currencies.
     */
    default java.util.Set<String> supportedCurrencies() {
        return java.util.Collections.emptySet();
    }

    /**
     * Fee rate in basis points (e.g. 30 = 0.30%). Used by cost-based routing.
     */
    default int feeBasisPoints() {
        return 0;
    }

    /**
     * 返回该 connector 的最终性策略。默认 null 表示无策略（兼容既有实现）。
     * Connector 实现可覆盖此方法以提供通道特定的最终性判定逻辑。
     *
     * @return 最终性策略实例，或 null（无策略）
     */
    default org.nexus.gateway.orchestration.settlement.FinalityPolicy getFinalityPolicy() {
        return null;
    }
}