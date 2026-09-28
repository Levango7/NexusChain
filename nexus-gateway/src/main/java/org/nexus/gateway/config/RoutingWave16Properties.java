package org.nexus.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Wave 16 路由增强配置 — prefix = "nexus.routing"
 *
 * <p>包含 7 个嵌套配置组，分别对应 Wave 16 各模块：</p>
 * <ul>
 *   <li>{@link Strategy} — 多目标路由策略权重与热加载</li>
 *   <li>{@link Health} — Connector 健康评估参数</li>
 *   <li>{@link Fallback} — 降级与重试策略</li>
 *   <li>{@link Experiment} — A/B 实验框架</li>
 *   <li>{@link Audit} — 路由审计日志</li>
 *   <li>{@link Profile} — 路由画像与金额分层</li>
 *   <li>{@link Monitor} — 路由监控与异常检测</li>
 * </ul>
 *
 * <p>未启用 Wave 16 时（各 enabled=false），行为逐字节等价于 Wave 15。</p>
 */
@ConfigurationProperties(prefix = "nexus.routing")
public class RoutingWave16Properties {

    private Strategy strategy = new Strategy();
    private Health health = new Health();
    private Fallback fallback = new Fallback();
    private Experiment experiment = new Experiment();
    private Audit audit = new Audit();
    private Profile profile = new Profile();
    private Monitor monitor = new Monitor();

    // ===== 外层 getter/setter =====

    public Strategy getStrategy() { return strategy; }
    public void setStrategy(Strategy strategy) { this.strategy = strategy; }

    public Health getHealth() { return health; }
    public void setHealth(Health health) { this.health = health; }

    public Fallback getFallback() { return fallback; }
    public void setFallback(Fallback fallback) { this.fallback = fallback; }

    public Experiment getExperiment() { return experiment; }
    public void setExperiment(Experiment experiment) { this.experiment = experiment; }

    public Audit getAudit() { return audit; }
    public void setAudit(Audit audit) { this.audit = audit; }

    public Profile getProfile() { return profile; }
    public void setProfile(Profile profile) { this.profile = profile; }

    public Monitor getMonitor() { return monitor; }
    public void setMonitor(Monitor monitor) { this.monitor = monitor; }

    // ===== Strategy 嵌套配置 =====

    /**
     * 多目标路由策略配置 — MULTI_OBJECTIVE 策略的权重与热加载参数。
     */
    public static class Strategy {
        private boolean enabled = true;
        private double defaultCostWeight = 0.25;
        private double defaultSuccessRateWeight = 0.35;
        private double defaultLatencyWeight = 0.25;
        private double defaultRiskWeight = 0.15;
        private String hotReloadInterval = "5s";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getDefaultCostWeight() { return defaultCostWeight; }
        public void setDefaultCostWeight(double defaultCostWeight) { this.defaultCostWeight = defaultCostWeight; }

        public double getDefaultSuccessRateWeight() { return defaultSuccessRateWeight; }
        public void setDefaultSuccessRateWeight(double defaultSuccessRateWeight) { this.defaultSuccessRateWeight = defaultSuccessRateWeight; }

        public double getDefaultLatencyWeight() { return defaultLatencyWeight; }
        public void setDefaultLatencyWeight(double defaultLatencyWeight) { this.defaultLatencyWeight = defaultLatencyWeight; }

        public double getDefaultRiskWeight() { return defaultRiskWeight; }
        public void setDefaultRiskWeight(double defaultRiskWeight) { this.defaultRiskWeight = defaultRiskWeight; }

        public String getHotReloadInterval() { return hotReloadInterval; }
        public void setHotReloadInterval(String hotReloadInterval) { this.hotReloadInterval = hotReloadInterval; }
    }

    // ===== Health 嵌套配置 =====

    /**
     * Connector 健康评估配置 — 评估周期、历史采样与权重。
     */
    public static class Health {
        private boolean enabled = true;
        private String evaluationInterval = "30s";
        private String historySamplingInterval = "5m";
        private int historyRetentionDays = 30;
        private double successRateWeight = 0.35;
        private double latencyWeight = 0.25;
        private double errorRateWeight = 0.25;
        private double capacityWeight = 0.15;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getEvaluationInterval() { return evaluationInterval; }
        public void setEvaluationInterval(String evaluationInterval) { this.evaluationInterval = evaluationInterval; }

        public String getHistorySamplingInterval() { return historySamplingInterval; }
        public void setHistorySamplingInterval(String historySamplingInterval) { this.historySamplingInterval = historySamplingInterval; }

        public int getHistoryRetentionDays() { return historyRetentionDays; }
        public void setHistoryRetentionDays(int historyRetentionDays) { this.historyRetentionDays = historyRetentionDays; }

        public double getSuccessRateWeight() { return successRateWeight; }
        public void setSuccessRateWeight(double successRateWeight) { this.successRateWeight = successRateWeight; }

        public double getLatencyWeight() { return latencyWeight; }
        public void setLatencyWeight(double latencyWeight) { this.latencyWeight = latencyWeight; }

        public double getErrorRateWeight() { return errorRateWeight; }
        public void setErrorRateWeight(double errorRateWeight) { this.errorRateWeight = errorRateWeight; }

        public double getCapacityWeight() { return capacityWeight; }
        public void setCapacityWeight(double capacityWeight) { this.capacityWeight = capacityWeight; }
    }

    // ===== Fallback 嵌套配置 =====

    /**
     * 降级与重试策略配置 — 最大重试次数、退避与超时。
     */
    public static class Fallback {
        private boolean enabled = true;
        private int maxRetries = 3;
        private String retryBackoff = "100ms";
        private String retryTimeout = "10s";
        private String totalTimeout = "30s";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getMaxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

        public String getRetryBackoff() { return retryBackoff; }
        public void setRetryBackoff(String retryBackoff) { this.retryBackoff = retryBackoff; }

        public String getRetryTimeout() { return retryTimeout; }
        public void setRetryTimeout(String retryTimeout) { this.retryTimeout = retryTimeout; }

        public String getTotalTimeout() { return totalTimeout; }
        public void setTotalTimeout(String totalTimeout) { this.totalTimeout = totalTimeout; }
    }

    // ===== Experiment 嵌套配置 =====

    /**
     * A/B 实验框架配置 — 显著性阈值与最小样本量。
     */
    public static class Experiment {
        private boolean enabled = true;
        private double defaultSignificanceThreshold = 0.05;
        private int defaultMinSampleSize = 1000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getDefaultSignificanceThreshold() { return defaultSignificanceThreshold; }
        public void setDefaultSignificanceThreshold(double defaultSignificanceThreshold) { this.defaultSignificanceThreshold = defaultSignificanceThreshold; }

        public int getDefaultMinSampleSize() { return defaultMinSampleSize; }
        public void setDefaultMinSampleSize(int defaultMinSampleSize) { this.defaultMinSampleSize = defaultMinSampleSize; }
    }

    // ===== Audit 嵌套配置 =====

    /**
     * 路由审计日志配置 — 异步写入与保留天数。
     */
    public static class Audit {
        private boolean enabled = true;
        private boolean asyncWrite = true;
        private int retentionDays = 90;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isAsyncWrite() { return asyncWrite; }
        public void setAsyncWrite(boolean asyncWrite) { this.asyncWrite = asyncWrite; }

        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
    }

    // ===== Profile 嵌套配置 =====

    /**
     * 路由画像配置 — 金额分层阈值与高峰时段。
     */
    public static class Profile {
        private boolean enabled = true;
        private double smallAmountThreshold = 100;
        private double largeAmountThreshold = 10000;
        private String peakHoursStart = "09:00";
        private String peakHoursEnd = "22:00";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public double getSmallAmountThreshold() { return smallAmountThreshold; }
        public void setSmallAmountThreshold(double smallAmountThreshold) { this.smallAmountThreshold = smallAmountThreshold; }

        public double getLargeAmountThreshold() { return largeAmountThreshold; }
        public void setLargeAmountThreshold(double largeAmountThreshold) { this.largeAmountThreshold = largeAmountThreshold; }

        public String getPeakHoursStart() { return peakHoursStart; }
        public void setPeakHoursStart(String peakHoursStart) { this.peakHoursStart = peakHoursStart; }

        public String getPeakHoursEnd() { return peakHoursEnd; }
        public void setPeakHoursEnd(String peakHoursEnd) { this.peakHoursEnd = peakHoursEnd; }
    }

    // ===== Monitor 嵌套配置 =====

    /**
     * 路由监控与异常检测配置 — 指标周期与异常阈值。
     */
    public static class Monitor {
        private boolean enabled = true;
        private String metricsInterval = "30s";
        private String anomalyDetectionInterval = "30s";
        private double successRateDropThreshold = 0.20;
        private double latencySpikeThreshold = 0.50;
        private int consecutiveFailureThreshold = 5;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getMetricsInterval() { return metricsInterval; }
        public void setMetricsInterval(String metricsInterval) { this.metricsInterval = metricsInterval; }

        public String getAnomalyDetectionInterval() { return anomalyDetectionInterval; }
        public void setAnomalyDetectionInterval(String anomalyDetectionInterval) { this.anomalyDetectionInterval = anomalyDetectionInterval; }

        public double getSuccessRateDropThreshold() { return successRateDropThreshold; }
        public void setSuccessRateDropThreshold(double successRateDropThreshold) { this.successRateDropThreshold = successRateDropThreshold; }

        public double getLatencySpikeThreshold() { return latencySpikeThreshold; }
        public void setLatencySpikeThreshold(double latencySpikeThreshold) { this.latencySpikeThreshold = latencySpikeThreshold; }

        public int getConsecutiveFailureThreshold() { return consecutiveFailureThreshold; }
        public void setConsecutiveFailureThreshold(int consecutiveFailureThreshold) { this.consecutiveFailureThreshold = consecutiveFailureThreshold; }
    }
}