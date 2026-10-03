package org.nexus.gateway.orchestration.routing.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.nexus.gateway.config.RoutingWave16Properties;
import org.nexus.gateway.orchestration.routing.ai.ConnectorMetrics;
import org.nexus.gateway.orchestration.routing.ai.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 路由监控与异常检测服务（Wave 16 模块七）。
 *
 * <p><b>指标</b>：Micrometer 计数器 {@code nexus_routing_decisions_total}
 * （tag: strategy）与 {@code nexus_routing_anomalies_total}（tag: type），
 * MeterRegistry 不可用时静默降级为无指标。</p>
 *
 * <p><b>异常检测</b>（默认每 30s，对齐 anomaly-detection-interval）：
 * 以连接器指标的指数滑动平均（EMA，α=0.2）为基线，检测三类异常：</p>
 * <ul>
 *   <li>SUCCESS_RATE_DROP：成功率较基线下降超过 {@code success-rate-drop-threshold}（默认 0.20）；</li>
 *   <li>LATENCY_SPIKE：平均延迟较基线上升超过 {@code latency-spike-threshold}（默认 +0.50）；</li>
 *   <li>CONSECUTIVE_FAILURES：连续失败桶 ≥ {@code consecutive-failure-threshold}（默认 5）。</li>
 * </ul>
 *
 * <p>异常通过 ERROR/WARN 日志 + 计数器暴露，并保留在
 * {@link #recentAnomalies()}（内存最近 100 条，进程级诊断用）。</p>
 */
@Service
public class RoutingMonitorService {

    private static final Logger log = LoggerFactory.getLogger(RoutingMonitorService.class);

    /** EMA 平滑系数：新值权重（0.2 ≈ 近 5 个观测窗口的加权记忆）。 */
    static final double EMA_ALPHA = 0.2;
    /** 基线需要至少这么多样本才参与对比（冷启动期不告警）。 */
    static final long BASELINE_MIN_SAMPLES = 10;
    /** 内存保留的最近异常条数。 */
    private static final int MAX_RECENT_ANOMALIES = 100;

    /** 异常类型。 */
    public enum AnomalyType { SUCCESS_RATE_DROP, LATENCY_SPIKE, CONSECUTIVE_FAILURES }

    /** 一条异常记录。 */
    public record Anomaly(String connectorId, AnomalyType type, String detail, Instant detectedAt) {}

    /** 基线（EMA）。 */
    private static final class Baseline {
        volatile double successRate = -1;
        volatile long avgLatencyMs = -1;
    }

    private final RoutingWave16Properties properties;
    private final MetricsCollector metricsCollector;
    private final MeterRegistry meterRegistry;

    private final Map<String, Baseline> baselines = new ConcurrentHashMap<>();
    private final List<Anomaly> recentAnomalies = new ArrayList<>();

    public RoutingMonitorService(RoutingWave16Properties properties) {
        this(properties, null, null);
    }

    @Autowired
    public RoutingMonitorService(RoutingWave16Properties properties,
                                 @Autowired(required = false) MetricsCollector metricsCollector,
                                 @Autowired(required = false) MeterRegistry meterRegistry) {
        this.properties = properties;
        this.metricsCollector = metricsCollector;
        this.meterRegistry = meterRegistry;
    }

    // ==================== 决策计数 ====================

    /** 路由决策计数（由引擎在每次决策后调用，monitor 未启用时为 no-op）。 */
    public void incrementDecisions(String strategy) {
        if (meterRegistry == null) return;
        try {
            meterRegistry.counter("nexus_routing_decisions_total", "strategy", strategy)
                    .increment();
        } catch (RuntimeException e) {
            log.debug("Decision counter failed: {}", e.getMessage());
        }
    }

    // ==================== 异常检测 ====================

    /**
     * 定时异常检测（每 30s，对齐 anomaly-detection-interval 默认值）。
     * ShedLock：多实例仅一个执行检测（ADR-034 §2 阻塞项清偿）——EMA 基线是
     * 进程内状态，多实例各自检测会产生重复告警记录。
     */
    @Scheduled(fixedDelayString = "PT30S", initialDelay = 45000)
    @SchedulerLock(name = "routingMonitorAnomaly", lockAtMostFor = "PT1M", lockAtLeastFor = "PT15S")
    public void checkAnomalies() {
        if (!properties.getMonitor().isEnabled() || metricsCollector == null) return;
        for (ConnectorMetrics metrics : metricsCollector.metricsAll()) {
            try {
                checkConnector(metrics);
            } catch (RuntimeException e) {
                log.warn("Anomaly check failed for {}: {}", metrics.connectorId(), e.getMessage());
            }
        }
    }

    private void checkConnector(ConnectorMetrics metrics) {
        String connectorId = metrics.connectorId();
        RoutingWave16Properties.Monitor cfg = properties.getMonitor();

        if (metrics.samples() >= BASELINE_MIN_SAMPLES
                && metrics.recentFailures() >= cfg.getConsecutiveFailureThreshold()) {
            record(AnomalyType.CONSECUTIVE_FAILURES, connectorId,
                    "recentFailures=" + metrics.recentFailures());
        }

        Baseline baseline = baselines.computeIfAbsent(connectorId, k -> new Baseline());
        if (metrics.samples() >= BASELINE_MIN_SAMPLES && baseline.successRate >= 0) {
            double drop = baseline.successRate - metrics.successRate();
            if (drop > cfg.getSuccessRateDropThreshold()) {
                record(AnomalyType.SUCCESS_RATE_DROP, connectorId,
                        String.format("baseline=%.3f, current=%.3f", baseline.successRate, metrics.successRate()));
            }
            if (baseline.avgLatencyMs > 0) {
                double spike = (metrics.avgLatencyMs() - baseline.avgLatencyMs) / (double) baseline.avgLatencyMs;
                if (spike > cfg.getLatencySpikeThreshold()) {
                    record(AnomalyType.LATENCY_SPIKE, connectorId,
                            "baseline=" + baseline.avgLatencyMs + "ms, current=" + metrics.avgLatencyMs() + "ms");
                }
            }
        }

        // 更新 EMA 基线（有样本才参与）
        if (metrics.samples() > 0) {
            if (baseline.successRate < 0) {
                baseline.successRate = metrics.successRate();
                baseline.avgLatencyMs = metrics.avgLatencyMs();
            } else {
                baseline.successRate = EMA_ALPHA * metrics.successRate() + (1 - EMA_ALPHA) * baseline.successRate;
                baseline.avgLatencyMs = (long) (EMA_ALPHA * metrics.avgLatencyMs()
                        + (1 - EMA_ALPHA) * baseline.avgLatencyMs);
            }
        }
    }

    private void record(AnomalyType type, String connectorId, String detail) {
        Anomaly anomaly = new Anomaly(connectorId, type, detail, Instant.now());
        synchronized (recentAnomalies) {
            recentAnomalies.add(anomaly);
            if (recentAnomalies.size() > MAX_RECENT_ANOMALIES) {
                recentAnomalies.remove(0);
            }
        }
        if (type == AnomalyType.CONSECUTIVE_FAILURES) {
            log.error("Routing anomaly [{}] connector={}: {}", type, connectorId, detail);
        } else {
            log.warn("Routing anomaly [{}] connector={}: {}", type, connectorId, detail);
        }
        if (meterRegistry != null) {
            try {
                meterRegistry.counter("nexus_routing_anomalies_total", "type", type.name()).increment();
            } catch (RuntimeException e) {
                log.debug("Anomaly counter failed: {}", e.getMessage());
            }
        }
    }

    /** 最近异常（内存快照，最早在前）。 */
    public List<Anomaly> recentAnomalies() {
        synchronized (recentAnomalies) {
            return List.copyOf(recentAnomalies);
        }
    }

    /** 清空基线（测试辅助 / 配置变更后重置）。 */
    public void resetBaselines() {
        baselines.clear();
    }
}
