package org.nexus.gateway.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import org.springframework.stereotype.Component;

/**
 * B3: Custom business metrics for NexusChain Gateway.
 * Exposes payment funnel, latency, and error metrics to Prometheus via Actuator.
 */
@Component
public class PaymentMetrics {

    private final Counter ordersCreated;
    private final Counter paymentsConfirmed;
    private final Counter paymentsFailed;
    private final Counter refundsIssued;
    private final Counter webhookDelivered;
    private final Counter webhookFailed;
    private final Timer paymentLatency;

    public PaymentMetrics(MeterRegistry registry) {
        this.ordersCreated = Counter.builder("nexus.orders.created")
                .description("Total orders created").register(registry);
        this.paymentsConfirmed = Counter.builder("nexus.payments.confirmed")
                .description("Total payments confirmed").register(registry);
        this.paymentsFailed = Counter.builder("nexus.payments.failed")
                .description("Total payments failed").register(registry);
        this.refundsIssued = Counter.builder("nexus.refunds.issued")
                .description("Total refunds issued").register(registry);
        this.webhookDelivered = Counter.builder("nexus.webhooks.delivered")
                .description("Webhooks successfully delivered").register(registry);
        this.webhookFailed = Counter.builder("nexus.webhooks.failed")
                .description("Webhooks failed to deliver").register(registry);
        this.paymentLatency = Timer.builder("nexus.payment.latency")
                .description("Time from order creation to payment confirmation").register(registry);
    }

    public void orderCreated() { ordersCreated.increment(); }
    public void paymentConfirmed() { paymentsConfirmed.increment(); }
    public void paymentFailed() { paymentsFailed.increment(); }
    public void refundIssued() { refundsIssued.increment(); }
    public void webhookDelivered() { webhookDelivered.increment(); }
    public void webhookFailed() { webhookFailed.increment(); }
    public Timer getPaymentLatency() { return paymentLatency; }
}