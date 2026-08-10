package org.nexus.gateway.orchestration.routing.ai;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A/B 测试指标聚合：AI 组 vs 规则组的成功率/延迟对比。
 *
 * <p><b>聚合维度</b>：按 {@code routingMethod}（"ai" / "rule"）分组，统计：</p>
 * <ul>
 *   <li>总决策次数</li>
 *   <li>成功次数（outcome=SUCCESS）</li>
 *   <li>失败次数（outcome=FAILURE）</li>
 *   <li>累计延迟（用于计算平均延迟）</li>
 * </ul>
 *
 * <p><b>线程安全</b>：使用 {@link AtomicInteger} / {@link AtomicLong}，无锁并发更新。
 * {@link #snapshot} 返回不可变快照 {@link GroupStats}。</p>
 *
 * <p><b>对比指标</b>：{@link #comparison} 返回 AI 组相对规则组的成功率差值与
 * 平均延迟差值，正值表示 AI 组更优。</p>
 */
public class AbTestMetrics {

    private final AtomicInteger aiTotal = new AtomicInteger();
    private final AtomicInteger aiSuccess = new AtomicInteger();
    private final AtomicInteger aiFailure = new AtomicInteger();
    private final AtomicLong aiLatencySum = new AtomicLong();

    private final AtomicInteger ruleTotal = new AtomicInteger();
    private final AtomicInteger ruleSuccess = new AtomicInteger();
    private final AtomicInteger ruleFailure = new AtomicInteger();
    private final AtomicLong ruleLatencySum = new AtomicLong();

    /**
     * 记入一条 A/B 测试结果。仅统计已完成（SUCCESS/FAILURE）的记录。
     */
    public void record(AbTestResult result) {
        if (result == null || result.outcome() == AbTestResult.Outcome.PENDING) return;
        boolean isAi = result.isAiGroup();
        if (isAi) {
            aiTotal.incrementAndGet();
            aiLatencySum.addAndGet(result.latencyMs());
            if (result.outcome() == AbTestResult.Outcome.SUCCESS) {
                aiSuccess.incrementAndGet();
            } else {
                aiFailure.incrementAndGet();
            }
        } else if (result.isRuleGroup()) {
            ruleTotal.incrementAndGet();
            ruleLatencySum.addAndGet(result.latencyMs());
            if (result.outcome() == AbTestResult.Outcome.SUCCESS) {
                ruleSuccess.incrementAndGet();
            } else {
                ruleFailure.incrementAndGet();
            }
        }
    }

    public GroupStats aiGroup() {
        return snapshot(aiTotal, aiSuccess, aiFailure, aiLatencySum);
    }

    public GroupStats ruleGroup() {
        return snapshot(ruleTotal, ruleSuccess, ruleFailure, ruleLatencySum);
    }

    /**
     * AI 组 vs 规则组对比。
     *
     * @return {@code successRateDiff > 0} 表示 AI 组成功率高；
     *         {@code avgLatencyDiff < 0} 表示 AI 组延迟低（更优）
     */
    public Comparison comparison() {
        GroupStats ai = aiGroup();
        GroupStats rule = ruleGroup();
        return new Comparison(
                ai.successRate - rule.successRate,
                ai.avgLatencyMs - rule.avgLatencyMs,
                ai, rule);
    }

    private static GroupStats snapshot(AtomicInteger total, AtomicInteger success,
                                       AtomicInteger failure, AtomicLong latencySum) {
        int t = total.get();
        int s = success.get();
        int f = failure.get();
        long sum = latencySum.get();
        double rate = t == 0 ? 0.0 : (double) s / t;
        long avg = t == 0 ? 0L : sum / t;
        return new GroupStats(t, s, f, rate, avg);
    }

    /** 单组统计快照。 */
    public record GroupStats(int total, int success, int failure,
                             double successRate, long avgLatencyMs) {}

    /** 两组对比结果。 */
    public record Comparison(double successRateDiff, long avgLatencyDiff,
                             GroupStats ai, GroupStats rule) {

        /** AI 组是否在成功率上优于规则组。 */
        public boolean aiWinsOnSuccessRate() {
            return successRateDiff > 0;
        }

        /** AI 组是否在延迟上优于规则组（延迟更低）。 */
        public boolean aiWinsOnLatency() {
            return avgLatencyDiff < 0;
        }
    }
}