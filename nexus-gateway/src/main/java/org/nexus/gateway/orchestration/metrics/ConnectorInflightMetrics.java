package org.nexus.gateway.orchestration.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 连接器在途调用计数（A5 缺口：gateway 生产代码「零连接器在途并发计数」实现）。
 *
 * <p><b>采集口径</b>：以「进 start、未到 end」表示一次在途 call——一次受理
 * 在 {@code OrchestrationService#createPayment(Long, long, ...)} 里包裹
 * {@code connector.createPayment(req)}，连接器返回/抛异常后立刻减 1。多并发
 * 请求各自计数，互不干扰。
 *
 * <p><b>时序纪律</b>：{@code start}/{@code end} 必须成对。{@code end} 任何场景必调
 * （包含抛异常/超时），否则计数泄漏；业务代码只允许包在同一个 {@code try { … } finally}
 * 里，见 {@code OrchestrationService} 的 connector 循环。
 *
 * <p><b>Prometheus 导出名</b>（OpenMetrics）：
 * {@code nexus_gateway_connector_inflight{connector="<id>"}}，每个连接 id 一个
 * 时间序列（连接总数预期个位数，标签按注册顺序固定，不带 host/uid 扇出）。
 *
 * <p><b>使用纪律</b>：新增/删除连接器（{@code ConnectorRegistry.register()} /
 * {@code unregister()}）时 <b>不会</b> 自动注册/注销 Gauge——这是有意的设计：
 * 指标卡的创建会污染抓取面板，而 metric 数量 ≈ 连接器数量 × 节点数，连接器
 * 列表在 demo/prod 不固定会让告警错读成"异常波动"。如需动态注册 / 注销，应在
 * 该类里补充对应逻辑并同步 {@code docs/ops/slo.md}。
 */
@Component
public class ConnectorInflightMetrics {

    public static final String METRIC = "nexus.gateway.connector.inflight";
    private static final Logger log = LoggerFactory.getLogger(ConnectorInflightMetrics.class);

    private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    public ConnectorInflightMetrics(List<PaymentConnector> connectors, MeterRegistry registry) {
        for (PaymentConnector c : connectors) {
            AtomicInteger gauge = new AtomicInteger();
            inFlight.put(c.getId(), gauge);
            Gauge.builder(METRIC, gauge, AtomicInteger::get)
                    .tag("connector", c.getId())
                    .description("连接器在途调用计数（实时值）")
                    .register(registry);
        }
        log.info("ConnectorInflightMetrics bound {} connector(s)", inFlight.size());
    }

    /** 一次调用进入连接器时调用。 */
    public void start(String connectorId) {
        AtomicInteger g = inFlight.get(connectorId);
        if (g != null) {
            g.incrementAndGet();
        }
    }

    /** 调用结束（无论成功/异常/超时）时调用。异常路径也要保证减 1。 */
    public void end(String connectorId) {
        AtomicInteger g = inFlight.get(connectorId);
        if (g != null) {
            g.decrementAndGet();
        }
    }

    /** 当前在该连接器上跑的调用数。 */
    public int current(String connectorId) {
        AtomicInteger g = inFlight.get(connectorId);
        return g == null ? 0 : g.get();
    }
}
