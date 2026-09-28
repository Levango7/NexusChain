package org.nexus.gateway.orchestration.routing.experiment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import org.nexus.gateway.config.RoutingWave16Properties;
import org.nexus.gateway.orchestration.routing.RoutingJsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 路由 A/B 实验服务（Wave 16 模块四）。
 *
 * <p><b>分流</b>：对 {@code assign(experimentId, paymentId)} 以 paymentId+experimentId
 * 做稳定哈希（{@code String.hashCode}，跨 JVM 一致）映射到 [0,100) 桶位；
 * 各实验组按 weight 累积占桶，剩余桶位归对照组。同 paymentId 每次分配结果一致。</p>
 *
 * <p><b>统计</b>：{@code recordOutcome} 聚合到进程内每组计数器（重启清零——
 * 实验指标以运行期累积为准，跨重启的持久化统计为后续项）。</p>
 *
 * <p><b>显著性</b>：SUCCESS_RATE 目标使用两比例 z 检验（正态近似，
 * erf 采用 Abramowitz-Stegun 7.1.26 数值近似，|z| &gt; 6 时精度足够）；
 * LATENCY / COST 目标仅报告组间均值（不做显著性判定，诚实降级）。
 * 任一组样本量 &lt; minSampleSize 时结论为 INSUFFICIENT_SAMPLE。</p>
 */
@Service
public class RoutingExperimentService {

    private static final Logger log = LoggerFactory.getLogger(RoutingExperimentService.class);

    private final RoutingExperimentRepository repository;
    private final RoutingWave16Properties properties;

    /** 每组运行期统计：key = experimentId + "|" + groupId。 */
    private final Map<String, GroupStats> stats = new ConcurrentHashMap<>();

    public RoutingExperimentService(RoutingExperimentRepository repository,
                                    RoutingWave16Properties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    // ==================== 模型 ====================

    /** 实验组配置（JSON 反序列化载体）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ExperimentGroup {
        private String groupId;
        private String description;
        private int weight;
        private List<String> connectorIds = new ArrayList<>();

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public int getWeight() { return weight; }
        public void setWeight(int weight) { this.weight = weight; }
        public List<String> getConnectorIds() { return connectorIds; }
        public void setConnectorIds(List<String> connectorIds) { this.connectorIds = connectorIds; }
    }

    /** 分配结果。 */
    public record Assignment(String experimentId, String groupId, boolean control, List<String> connectorIds) {
    }

    /** 单组统计快照。 */
    public record GroupStat(long count, long successes, double successRate,
                            double avgLatencyMs, double avgCostBps) {
    }

    /** 显著性评估结论。 */
    public record Evaluation(String experimentId, String conclusion, String winnerGroupId,
                             Map<String, GroupStat> groupStats, double pValue) {

        public static final String CONCLUSION_INSUFFICIENT = "INSUFFICIENT_SAMPLE";
        public static final String CONCLUSION_WINNER = "WINNER";
        public static final String CONCLUSION_NO_DIFFERENCE = "NO_SIGNIFICANT_DIFFERENCE";
        public static final String CONCLUSION_MEANS_ONLY = "MEANS_REPORTED_NO_TEST";
    }

    /** 运行期组统计（原子累加）。 */
    static final class GroupStats {
        final AtomicLong count = new AtomicLong();
        final AtomicLong successes = new AtomicLong();
        final AtomicLong totalLatency = new AtomicLong();
        final AtomicLong totalCost = new AtomicLong();
    }

    // ==================== 分流 ====================

    /**
     * 为支付分配实验组（仅 RUNNING 且在时间窗口内的实验参与分流）。
     *
     * @return 空表示无活跃实验或分配到对照组
     */
    public Optional<Assignment> assign(String experimentId, String paymentId) {
        RoutingExperiment experiment = repository.findById(experimentId).orElse(null);
        if (experiment == null) return Optional.empty();
        return assignIfActive(experiment, paymentId);
    }

    /**
     * 引擎钩子：对全部活跃实验逐一尝试分配，返回第一个实验组命中
     * （对照组返回 Optional.empty，由调用方沿用规则结果）。
     */
    public Optional<Assignment> activeAssignment(String paymentId) {
        for (RoutingExperiment experiment : repository.findByStatus(RoutingExperiment.Status.RUNNING.name())) {
            Optional<Assignment> assignment = assignIfActive(experiment, paymentId);
            if (assignment.isPresent() && !assignment.get().control()) {
                return assignment;
            }
        }
        return Optional.empty();
    }

    private Optional<Assignment> assignIfActive(RoutingExperiment experiment, String paymentId) {
        if (!RoutingExperiment.Status.RUNNING.name().equals(experiment.getStatus())) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        if (experiment.getStartTime() != null && now.isBefore(experiment.getStartTime())) return Optional.empty();
        if (experiment.getEndTime() != null && now.isAfter(experiment.getEndTime())) return Optional.empty();
        if (paymentId == null || paymentId.isBlank()) return Optional.empty();

        List<ExperimentGroup> groups = parseGroups(experiment.getExperimentGroupsJson());
        int bucket = Math.floorMod((paymentId + "::" + experiment.getExperimentId()).hashCode(), 100);
        int controlWeight = 100 - groups.stream().mapToInt(ExperimentGroup::getWeight).sum();
        if (bucket < Math.max(0, controlWeight)) {
            return Optional.of(new Assignment(experiment.getExperimentId(),
                    controlGroupId(experiment), true, controlConnectorIds(experiment)));
        }
        int cumulative = Math.max(0, controlWeight);
        for (ExperimentGroup group : groups) {
            cumulative += group.getWeight();
            if (bucket < cumulative) {
                return Optional.of(new Assignment(experiment.getExperimentId(),
                        group.getGroupId(), false, group.getConnectorIds()));
            }
        }
        // 权重和溢出 100 时兜底归对照组
        return Optional.of(new Assignment(experiment.getExperimentId(),
                controlGroupId(experiment), true, controlConnectorIds(experiment)));
    }

    private String controlGroupId(RoutingExperiment experiment) {
        ExperimentGroup control = parseGroup(experiment.getControlGroupJson());
        return control != null && control.getGroupId() != null ? control.getGroupId() : "control";
    }

    private List<String> controlConnectorIds(RoutingExperiment experiment) {
        ExperimentGroup control = parseGroup(experiment.getControlGroupJson());
        return control != null && control.getConnectorIds() != null ? control.getConnectorIds() : List.of();
    }

    // ==================== 统计 ====================

    /** 记录一条实验结果（支付完成回调）。实验不存在时静默忽略（分流与回填竞态容忍）。 */
    public void recordOutcome(String experimentId, String groupId, boolean success,
                              long latencyMs, int costBps) {
        if (experimentId == null || groupId == null) return;
        GroupStats s = stats.computeIfAbsent(experimentId + "|" + groupId, k -> new GroupStats());
        s.count.incrementAndGet();
        if (success) s.successes.incrementAndGet();
        s.totalLatency.addAndGet(Math.max(0, latencyMs));
        s.totalCost.addAndGet(Math.max(0, costBps));
    }

    /**
     * 显著性评估。
     *
     * <p>SUCCESS_RATE：对照组 vs 最优实验组（按成功率），两比例 z 检验，
     * p &lt; significance_threshold 判定显著并给出 winner；
     * LATENCY / COST：仅报告均值（不做显著性判定）。</p>
     */
    public Evaluation evaluate(String experimentId) {
        RoutingExperiment experiment = repository.findById(experimentId)
                .orElseThrow(() -> new IllegalArgumentException("实验不存在: " + experimentId));
        int minSample = experiment.getMinSampleSize() > 0
                ? experiment.getMinSampleSize()
                : properties.getExperiment().getDefaultMinSampleSize();

        Map<String, GroupStat> groupStats = new LinkedHashMap<>();
        groupStats.put(controlGroupId(experiment), statOf(experimentId, controlGroupId(experiment)));
        for (ExperimentGroup group : parseGroups(experiment.getExperimentGroupsJson())) {
            groupStats.put(group.getGroupId(), statOf(experimentId, group.getGroupId()));
        }

        boolean allSufficient = groupStats.values().stream().allMatch(s -> s.count() >= minSample);
        if (!allSufficient) {
            return new Evaluation(experimentId, Evaluation.CONCLUSION_INSUFFICIENT, null, groupStats, Double.NaN);
        }

        RoutingExperiment.TargetMetric metric;
        try {
            metric = RoutingExperiment.TargetMetric.valueOf(experiment.getTargetMetric());
        } catch (IllegalArgumentException e) {
            metric = RoutingExperiment.TargetMetric.SUCCESS_RATE;
        }

        if (metric != RoutingExperiment.TargetMetric.SUCCESS_RATE) {
            return new Evaluation(experimentId, Evaluation.CONCLUSION_MEANS_ONLY, null, groupStats, Double.NaN);
        }

        GroupStat control = groupStats.get(controlGroupId(experiment));
        String bestGroupId = null;
        double bestRate = -1;
        for (Map.Entry<String, GroupStat> e : groupStats.entrySet()) {
            if (e.getKey().equals(controlGroupId(experiment))) continue;
            if (e.getValue().successRate() > bestRate) {
                bestRate = e.getValue().successRate();
                bestGroupId = e.getKey();
            }
        }
        if (bestGroupId == null) {
            return new Evaluation(experimentId, Evaluation.CONCLUSION_NO_DIFFERENCE, null, groupStats, Double.NaN);
        }
        GroupStat treatment = groupStats.get(bestGroupId);
        double pValue = twoProportionZTestPValue(control.successRate(), control.count(),
                treatment.successRate(), treatment.count());
        double threshold = experiment.getSignificanceThreshold() > 0
                ? experiment.getSignificanceThreshold()
                : properties.getExperiment().getDefaultSignificanceThreshold();
        if (pValue < threshold) {
            return new Evaluation(experimentId, Evaluation.CONCLUSION_WINNER, bestGroupId, groupStats, pValue);
        }
        return new Evaluation(experimentId, Evaluation.CONCLUSION_NO_DIFFERENCE, null, groupStats, pValue);
    }

    private GroupStat statOf(String experimentId, String groupId) {
        GroupStats s = stats.get(experimentId + "|" + groupId);
        if (s == null || s.count.get() == 0) {
            return new GroupStat(0, 0, 0.0, 0.0, 0.0);
        }
        long n = s.count.get();
        long successes = s.successes.get();
        return new GroupStat(n, successes, (double) successes / n,
                (double) s.totalLatency.get() / n, (double) s.totalCost.get() / n);
    }

    /**
     * 两比例 z 检验的双侧 p 值（正态近似）。
     *
     * @param p1 对照组成功率，n1 对照组样本量
     * @param p2 实验组成功率，n2 实验组样本量
     */
    static double twoProportionZTestPValue(double p1, long n1, double p2, long n2) {
        if (n1 <= 0 || n2 <= 0) return Double.NaN;
        double pooled = (p1 * n1 + p2 * n2) / (n1 + n2);
        double se = Math.sqrt(pooled * (1 - pooled) * (1.0 / n1 + 1.0 / n2));
        if (se == 0) return 1.0;
        double z = (p2 - p1) / se;
        double cdf = 0.5 * (1.0 + erf(Math.abs(z) / Math.sqrt(2)));
        return 2.0 * (1.0 - cdf);
    }

    /** Abramowitz-Stegun 7.1.26 误差函数近似（最大绝对误差 1.5e-7）。 */
    static double erf(double x) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(x));
        double y = t * Math.exp(-x * x - 1.26551223
                + t * (1.00002368
                + t * (0.37409196
                + t * (0.09678418
                + t * (-0.18628806
                + t * (0.27886807
                + t * (-1.13520398
                + t * (1.48851587
                + t * (-0.82215223
                + t * 0.17087277)))))))));
        return x >= 0 ? 1.0 - y : y - 1.0;
    }

    // ==================== 生命周期 ====================

    public List<RoutingExperiment> listAll() {
        return repository.findAll();
    }

    public List<RoutingExperiment> listRunning() {
        return repository.findByStatus(RoutingExperiment.Status.RUNNING.name());
    }

    public RoutingExperiment get(String experimentId) {
        return repository.findById(experimentId)
                .orElseThrow(() -> new IllegalArgumentException("实验不存在: " + experimentId));
    }

    @Transactional
    public RoutingExperiment create(RoutingExperiment experiment, String operator) {
        if (experiment.getTargetMetric() == null) {
            experiment.setTargetMetric(RoutingExperiment.TargetMetric.SUCCESS_RATE.name());
        }
        validate(experiment);
        experiment.setExperimentId(experiment.getExperimentId() == null || experiment.getExperimentId().isBlank()
                ? "exp_" + java.util.UUID.randomUUID()
                : experiment.getExperimentId());
        experiment.setStatus(RoutingExperiment.Status.CREATED.name());
        experiment.setCreatedBy(operator);
        RoutingExperiment saved = repository.save(experiment);
        log.info("创建路由实验: id={}, name={}, operator={}", saved.getExperimentId(), saved.getName(), operator);
        return saved;
    }

    @Transactional
    public RoutingExperiment start(String experimentId) {
        RoutingExperiment experiment = get(experimentId);
        String status = experiment.getStatus();
        if (!RoutingExperiment.Status.CREATED.name().equals(status)
                && !RoutingExperiment.Status.PAUSED.name().equals(status)) {
            throw new IllegalStateException("仅 CREATED/PAUSED 状态可启动: " + experimentId + " (" + status + ")");
        }
        experiment.setStatus(RoutingExperiment.Status.RUNNING.name());
        if (experiment.getStartTime() == null) {
            experiment.setStartTime(LocalDateTime.now());
        }
        RoutingExperiment saved = repository.save(experiment);
        log.info("路由实验启动: id={}", experimentId);
        return saved;
    }

    @Transactional
    public RoutingExperiment pause(String experimentId) {
        RoutingExperiment experiment = get(experimentId);
        if (!RoutingExperiment.Status.RUNNING.name().equals(experiment.getStatus())) {
            throw new IllegalStateException("仅 RUNNING 状态可暂停: " + experimentId);
        }
        experiment.setStatus(RoutingExperiment.Status.PAUSED.name());
        RoutingExperiment saved = repository.save(experiment);
        log.info("路由实验暂停: id={}", experimentId);
        return saved;
    }

    @Transactional
    public RoutingExperiment finish(String experimentId, boolean completed) {
        RoutingExperiment experiment = get(experimentId);
        String status = experiment.getStatus();
        if (!RoutingExperiment.Status.RUNNING.name().equals(status)
                && !RoutingExperiment.Status.PAUSED.name().equals(status)) {
            throw new IllegalStateException("仅 RUNNING/PAUSED 状态可结束: " + experimentId);
        }
        experiment.setStatus((completed ? RoutingExperiment.Status.COMPLETED : RoutingExperiment.Status.TERMINATED).name());
        experiment.setEndTime(LocalDateTime.now());
        RoutingExperiment saved = repository.save(experiment);
        log.info("路由实验结束: id={}, status={}", experimentId, saved.getStatus());
        return saved;
    }

    // ==================== 解析与校验 ====================

    private List<ExperimentGroup> parseGroups(String json) {
        if (json == null || json.isBlank()) return List.of();
        return RoutingJsonCodec.readValue(json, new TypeReference<List<ExperimentGroup>>() {});
    }

    private ExperimentGroup parseGroup(String json) {
        if (json == null || json.isBlank()) return null;
        return RoutingJsonCodec.readValue(json, new TypeReference<ExperimentGroup>() {});
    }

    private void validate(RoutingExperiment experiment) {
        if (experiment.getName() == null || experiment.getName().isBlank()) {
            throw new IllegalArgumentException("实验名称不能为空");
        }
        if (experiment.getControlGroupJson() == null || experiment.getControlGroupJson().isBlank()) {
            throw new IllegalArgumentException("对照组配置不能为空");
        }
        ExperimentGroup control = parseGroup(experiment.getControlGroupJson());
        if (control == null) {
            throw new IllegalArgumentException("对照组配置 JSON 非法");
        }
        List<ExperimentGroup> groups = parseGroups(experiment.getExperimentGroupsJson());
        int weightSum = 0;
        for (ExperimentGroup group : groups) {
            if (group.getGroupId() == null || group.getGroupId().isBlank()) {
                throw new IllegalArgumentException("实验组 groupId 不能为空");
            }
            if (group.getWeight() < 0) {
                throw new IllegalArgumentException("实验组权重不能为负: " + group.getGroupId());
            }
            weightSum += group.getWeight();
        }
        if (weightSum > 100) {
            throw new IllegalArgumentException("实验组权重之和不能超过 100: " + weightSum);
        }
        if (experiment.getSignificanceThreshold() <= 0 || experiment.getSignificanceThreshold() >= 1) {
            throw new IllegalArgumentException("显著性阈值必须在 (0,1) 内");
        }
        if (experiment.getMinSampleSize() < 0) {
            throw new IllegalArgumentException("最小样本量不能为负");
        }
        try {
            RoutingExperiment.TargetMetric.valueOf(experiment.getTargetMetric() == null
                    ? RoutingExperiment.TargetMetric.SUCCESS_RATE.name() : experiment.getTargetMetric());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("目标指标非法: " + experiment.getTargetMetric());
        }
    }
}
