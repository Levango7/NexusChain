package org.nexus.gateway.orchestration.routing.health;

import org.nexus.gateway.config.RoutingWave16Properties;
import org.nexus.gateway.orchestration.connector.ConnectorConfigRepository;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.routing.ai.ConnectorMetrics;
import org.nexus.gateway.orchestration.routing.ai.MetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 渠道健康度服务（Wave 16 模块二）。
 *
 * <p>基于 {@link MetricsCollector} 的窗口指标 + connector 容量配置计算四维
 * 健康度评分（[0,1]，越大越好），并定时采样写入 {@code channel_health_history}：</p>
 * <ul>
 *   <li><b>successRateScore</b>：窗口成功率；无样本 → 0.5（中性）；</li>
 *   <li><b>latencyScore</b>：{@code 1 - avgLatencyMs / 5000}（5s 视为完全不健康）；
 *       无样本 → 0.5；</li>
 *   <li><b>errorRateScore</b>：按最近连续失败桶线性衰减（0 桶 → 1.0，
 *       ≥ {@value #CONSECUTIVE_FAILURE_CAP} 桶 → 0.0）；</li>
 *   <li><b>capacityScore</b>：{@code connector_configs.max_concurrent} 未配置 → 0.5（中性，
 *       与 V85 迁移注释一致）；已配置 → 1.0（网关暂未统计在途并发，容量占用追踪为后续项）。</li>
 * </ul>
 *
 * <p>综合分 = {@code nexus.routing.health.*-weight} 加权和（权重和不为 1 时内部归一化）。
 * 等级：≥0.70 HEALTHY，≥0.40 DEGRADED，否则 UNHEALTHY。</p>
 *
 * <p>调度说明：采样间隔/保留期通过 {@code @Scheduled} 的 ISO 时长常量控制，
 * 与 {@code nexus.routing.health.history-sampling-interval}（默认 5m）、
 * {@code history-retention-days}（默认 30）的默认值对齐；{@code enabled=false}
 * 时方法内部直接返回。</p>
 */
@Service
public class ChannelHealthService {

    private static final Logger log = LoggerFactory.getLogger(ChannelHealthService.class);

    /** 平均延迟达到该值（毫秒）时 latencyScore = 0。 */
    static final long LATENCY_UPPER_MS = 5000L;
    /** 连续失败桶达到该值时 errorRateScore = 0。 */
    static final int CONSECUTIVE_FAILURE_CAP = 5;

    /** 健康等级。 */
    public enum HealthLevel { HEALTHY, DEGRADED, UNHEALTHY }

    /**
     * 单个 connector 的健康度评分快照。
     */
    public record ConnectorHealthScore(
            String connectorId,
            double overallScore,
            double successRateScore,
            double latencyScore,
            double errorRateScore,
            double capacityScore,
            HealthLevel level,
            long samples) {

        public String levelName() { return level.name(); }
    }

    private final ChannelHealthHistoryRepository historyRepository;
    private final ConnectorConfigRepository connectorConfigRepository;
    private final RoutingWave16Properties properties;

    /** 可选协作方（AI 路由未启用/测试环境可为 null，全部 null 安全）。 */
    private MetricsCollector metricsCollector;
    private ConnectorRegistry connectorRegistry;

    @Autowired
    public ChannelHealthService(ChannelHealthHistoryRepository historyRepository,
                                ConnectorConfigRepository connectorConfigRepository,
                                RoutingWave16Properties properties) {
        this.historyRepository = historyRepository;
        this.connectorConfigRepository = connectorConfigRepository;
        this.properties = properties;
    }

    @Autowired(required = false)
    public void setMetricsCollector(MetricsCollector metricsCollector) {
        this.metricsCollector = metricsCollector;
    }

    @Autowired(required = false)
    public void setConnectorRegistry(ConnectorRegistry connectorRegistry) {
        this.connectorRegistry = connectorRegistry;
    }

    // ==================== 评分 ====================

    /**
     * 评估单个 connector 的健康度。
     *
     * @return 四维评分 + 综合分 + 等级；{@code nexus.routing.health.enabled=false}
     *         或指标源不可用时各维度取中性值 0.5（level=DEGRADED）
     */
    public ConnectorHealthScore evaluate(String connectorId) {
        double successScore = 0.5;
        double latencyScore = 0.5;
        double errorScore = 1.0;
        long samples = 0;

        if (properties.getHealth().isEnabled() && metricsCollector != null) {
            ConnectorMetrics m = metricsCollector.metrics(connectorId);
            if (m != null && m.samples() > 0) {
                samples = m.samples();
                successScore = clamp01(m.successRate());
                latencyScore = clamp01(1.0 - m.avgLatencyMs() / (double) LATENCY_UPPER_MS);
                errorScore = clamp01(1.0 - m.recentFailures() / (double) CONSECUTIVE_FAILURE_CAP);
            }
        } else if (!properties.getHealth().isEnabled()) {
            // 模块整体禁用：全部中性，保持行为可预期
            errorScore = 0.5;
        }

        double capacityScore = capacityScore(connectorId);
        double overall = weightedOverall(successScore, latencyScore, errorScore, capacityScore);
        HealthLevel level = classify(overall);
        return new ConnectorHealthScore(connectorId, overall, successScore, latencyScore,
                errorScore, capacityScore, level, samples);
    }

    /**
     * risk 维度评分（供多目标路由 {@code MultiObjectiveRoutingService} 使用）。
     * 评估异常时回退中性值 0.5，不阻断路由。
     */
    public double errorRateScore(String connectorId) {
        try {
            return evaluate(connectorId).errorRateScore();
        } catch (RuntimeException e) {
            log.debug("errorRateScore fallback for {}: {}", connectorId, e.getMessage());
            return 0.5;
        }
    }

    /** 评估全部已观测 connector（registry 优先，退回 metricsCollector 已观测集合）。 */
    public List<ConnectorHealthScore> evaluateAll() {
        List<String> ids = observedConnectorIds();
        List<ConnectorHealthScore> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            result.add(evaluate(id));
        }
        return result;
    }

    // ==================== 历史采样 ====================

    /**
     * 定时采样（每 5 分钟，对齐 history-sampling-interval 默认值）。
     * {@code nexus.routing.health.enabled=false} 时不采样。
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelay = 60000)
    public void sampleAll() {
        if (!properties.getHealth().isEnabled()) return;
        for (String connectorId : observedConnectorIds()) {
            try {
                recordSample(connectorId);
            } catch (RuntimeException e) {
                log.warn("Health sample failed for {}: {}", connectorId, e.getMessage());
            }
        }
    }

    /** 写入单条健康度历史样本。 */
    @Transactional
    public ChannelHealthHistory recordSample(String connectorId) {
        ConnectorHealthScore score = evaluate(connectorId);
        ChannelHealthHistory history = new ChannelHealthHistory();
        history.setConnectorId(connectorId);
        history.setOverallScore(score.overallScore());
        history.setSuccessRateScore(score.successRateScore());
        history.setLatencyScore(score.latencyScore());
        history.setErrorRateScore(score.errorRateScore());
        history.setCapacityScore(score.capacityScore());
        history.setLevel(score.level().name());
        history.setSampledAt(LocalDateTime.now());
        return historyRepository.save(history);
    }

    /**
     * 保留期清理（每小时）：删除超过 history-retention-days 的历史样本。
     */
    @Scheduled(fixedDelayString = "PT1H", initialDelay = 300000)
    @Transactional
    public void purgeExpiredHistory() {
        if (!properties.getHealth().isEnabled()) return;
        int days = Math.max(1, properties.getHealth().getHistoryRetentionDays());
        int removed = historyRepository.deleteBySampledAtBefore(LocalDateTime.now().minusDays(days));
        if (removed > 0) {
            log.info("Purged {} channel health history records older than {} days", removed, days);
        }
    }

    /** 查询 connector 最近历史（limit 条，sampled_at 降序）。 */
    public List<ChannelHealthHistory> history(String connectorId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return historyRepository.findByConnectorIdOrderBySampledAtDesc(connectorId, PageRequest.of(0, safeLimit));
    }

    // ==================== 内部 ====================

    private double capacityScore(String connectorId) {
        if (!properties.getHealth().isEnabled()) return 0.5;
        try {
            return connectorConfigRepository.findById(connectorId)
                    .map(config -> config.getMaxConcurrent() != null && config.getMaxConcurrent() > 0 ? 1.0 : 0.5)
                    .orElse(0.5);
        } catch (RuntimeException e) {
            log.debug("Capacity config unavailable for {}: {}", connectorId, e.getMessage());
            return 0.5;
        }
    }

    private double weightedOverall(double success, double latency, double error, double capacity) {
        RoutingWave16Properties.Health h = properties.getHealth();
        double wSum = h.getSuccessRateWeight() + h.getLatencyWeight()
                + h.getErrorRateWeight() + h.getCapacityWeight();
        if (wSum <= 0) {
            return 0.5 * (success + latency + error + capacity);
        }
        return (h.getSuccessRateWeight() * success
                + h.getLatencyWeight() * latency
                + h.getErrorRateWeight() * error
                + h.getCapacityWeight() * capacity) / wSum;
    }

    private HealthLevel classify(double overall) {
        if (overall >= 0.70) return HealthLevel.HEALTHY;
        if (overall >= 0.40) return HealthLevel.DEGRADED;
        return HealthLevel.UNHEALTHY;
    }

    private List<String> observedConnectorIds() {
        List<String> ids = new ArrayList<>();
        if (connectorRegistry != null) {
            for (PaymentConnector c : connectorRegistry.getAll()) {
                ids.add(c.getId());
            }
        } else if (metricsCollector != null) {
            for (ConnectorMetrics m : metricsCollector.metricsAll()) {
                ids.add(m.connectorId());
            }
        }
        return ids;
    }

    private double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
