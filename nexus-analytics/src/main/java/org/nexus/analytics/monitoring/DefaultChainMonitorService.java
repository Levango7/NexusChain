package org.nexus.analytics.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * {@link ChainMonitorService} 默认实现。
 *
 * <p>采集 → 求值 → 告警 三段式：
 * <ol>
 *   <li>从 {@link ChainMetricsProvider} 采集节点健康 / 区块传播 / 内存池指标</li>
 *   <li>对每组指标驱动注册的 {@link AlertRule} 求值</li>
 *   <li>命中的规则交由 {@link AlertService} 登记告警</li>
 * </ol>
 *
 * <p>{@link #startAll} 启动定时轮询（默认 30 秒），{@link #stopAll} 停止；
 * 也可通过 monitor* 方法单次触发，便于测试。
 */
@Slf4j
@Service
public class DefaultChainMonitorService implements ChainMonitorService {

    /** 默认采集间隔（秒） */
    private static final long DEFAULT_INTERVAL_SECONDS = 30;

    private final ChainMetricsProvider metricsProvider;
    private final AlertService alertService;

    /** 告警规则列表 */
    private final List<AlertRule> rules = new CopyOnWriteArrayList<>();

    /** 定时调度器（懒启动） */
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> scheduledTask;

    public DefaultChainMonitorService(ChainMetricsProvider metricsProvider, AlertService alertService) {
        this.metricsProvider = metricsProvider;
        this.alertService = alertService;
        registerDefaultRules();
    }

    @Override
    public void monitorNodeHealth() {
        Map<String, Object> metrics = metricsProvider.collectNodeHealth();
        evaluateRules("NODE_HEALTH", metrics);
    }

    @Override
    public void monitorBlockPropagation() {
        Map<String, Object> metrics = metricsProvider.collectBlockPropagation();
        evaluateRules("BLOCK_PROPAGATION", metrics);
    }

    @Override
    public void monitorMempool() {
        Map<String, Object> metrics = metricsProvider.collectMempool();
        evaluateRules("MEMPOOL", metrics);
    }

    @Override
    public void startAll() {
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            log.debug("Chain monitor already running");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "chain-monitor");
            t.setDaemon(true);
            return t;
        });
        scheduledTask = scheduler.scheduleAtFixedRate(this::runOnce,
                0, DEFAULT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        log.info("Chain monitor started: interval={}s", DEFAULT_INTERVAL_SECONDS);
    }

    @Override
    public void stopAll() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
        log.info("Chain monitor stopped");
    }

    /** 注册一条规则（运行时动态扩展）。 */
    public void registerRule(AlertRule rule) {
        if (rule != null) {
            rules.add(rule);
        }
    }

    /** 单次执行全部监控（定时任务与测试共用）。 */
    private void runOnce() {
        try {
            monitorNodeHealth();
            monitorBlockPropagation();
            monitorMempool();
        } catch (Exception e) {
            log.error("Chain monitor cycle failed", e);
        }
    }

    /** 对指标驱动规则求值并登记命中的告警。 */
    private void evaluateRules(String source, Map<String, Object> metrics) {
        for (AlertRule rule : rules) {
            rule.evaluate(metrics).ifPresent(alert -> {
                if (alert.getSource() == null || alert.getSource().isBlank()) {
                    alert.setSource(source);
                }
                alertService.raiseAlert(alert);
            });
        }
    }

    /** 注册默认规则集。 */
    private void registerDefaultRules() {
        rules.add(new ThresholdAlertRule(
                "node.sync.lag", "syncLag", 100,
                ThresholdAlertRule.Direction.ABOVE, Alert.Level.WARN, "NODE_HEALTH"));
        rules.add(new ThresholdAlertRule(
                "node.peer.count.low", "peerCount", 3,
                ThresholdAlertRule.Direction.BELOW, Alert.Level.CRITICAL, "NODE_HEALTH"));
        rules.add(new ThresholdAlertRule(
                "block.propagation.slow", "propagationP95Ms", 2000,
                ThresholdAlertRule.Direction.ABOVE, Alert.Level.WARN, "BLOCK_PROPAGATION"));
        rules.add(new ThresholdAlertRule(
                "mempool.congestion", "pendingCount", 10000,
                ThresholdAlertRule.Direction.ABOVE, Alert.Level.WARN, "MEMPOOL"));
    }

    /**
     * 获取当前注册规则数（测试 / 审计用）。
     *
     * @return 规则数
     */
    public int ruleCount() {
        return rules.size();
    }

    /**
     * 获取已注册规则名列表（测试 / 审计用）。
     *
     * @return 规则名列表
     */
    public List<String> ruleNames() {
        List<String> names = new ArrayList<>();
        for (AlertRule rule : rules) {
            names.add(rule.name());
        }
        return names;
    }
}
