package org.nexus.gateway.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * B3: Custom business metrics for NexusChain Gateway.
 * Exposes payment funnel, latency, and error metrics to Prometheus via Actuator.
 *
 * <h2>本类的指标必须「有人喂」</h2>
 *
 * <p>本类只负责 <b>注册</b> 指标；注册不等于有数据。2026-11-07 审计发现本类
 * 注册的 7 个 meter <b>全仓零调用点</b>：因为注册发生在 {@code @Component} 的
 * 构造器里，{@code /actuator/prometheus} <b>能看到这些序列、值恒为 0</b>——
 * 比「没有指标」更危险，因为面板看起来是通的。</p>
 *
 * <p>同类缺陷在本仓已有先例：{@code nexus_3ds_auth_total} 曾在
 * {@code SecurityMetricsRecorder} 里「注册后从来没人喂过，此前恒为 0」，
 * 后由 {@code ThreeDsAuthCompletedObserver} 补上调用点修复。</p>
 *
 * <p>因此：<b>新增 meter 时，同一 PR 内必须同时给出调用点</b>；
 * 调用点分布见 {@code docs/ops/slo.md} 的「立不起来的 SLO」一节。</p>
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
        // ⚠ 导出名不总是「注册名把点换成下划线再加 _total」：
        //   OpenMetrics 把 `_created` 保留给「创建时间戳」序列，Prometheus 客户端
        //   在导出时剥掉这个后缀 ⇒ nexus.orders.created 实际导出为 **nexus_orders_total**。
        //   2026-11-07 实测抓取文本确认；此前 Grafana 面板查 nexus_orders_created_total，
        //   该序列不存在 ⇒ 面板恒空。改名/新增 meter 前先看 PaymentMetricsTest 的抓取断言。
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

    /**
     * 记录「下单 → 支付确认」的端到端耗时。
     *
     * <p><b>口径（与 {@code ThreeDsAuthCompletedObserver} 一致：采不到就跳过，绝不记 0）</b>：
     * {@code createdAt} 为 null（兼容构造器/测试环境造出的订单没有该字段）或
     * 时间在未来（时钟回拨）时 <b>不记录任何样本</b>。把缺失写成 0 秒会让
     * p50/p95 看起来存在——那正是本类此前被诟病的「面板有值但值是假的」。</p>
     *
     * @param createdAt 订单创建时间；null 表示无法确定，跳过
     */
    public void paymentLatencySince(LocalDateTime createdAt) {
        if (createdAt == null) {
            return;
        }
        Duration elapsed = Duration.between(createdAt, LocalDateTime.now());
        if (elapsed.isNegative()) {
            return;
        }
        paymentLatency.record(elapsed);
    }
}
