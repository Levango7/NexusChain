package org.nexus.gateway.orchestration.routing.ai;

import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A/B 测试路由器：按配置比例将流量分配到 AI 路由和规则路由。
 *
 * <p><b>分流策略</b>：以 {@code aiTrafficPercentage}（0-100）为概率，对每次请求
 * 投掷随机数决定归属 AI 组还是规则组。使用 {@link ThreadLocalRandom} 避免竞争。
 * 当 {@code aiTrafficPercentage=0} 时全部走规则组；{@code =100} 时全部走 AI 组。</p>
 *
 * <p><b>降级逻辑</b>：当分流到 AI 组但 {@link AiRoutingStrategy#resolve} 返回空
 * （样本不足/模型异常），自动降级到规则组，并记录降级事件。最终返回的
 * {@link Decision} 包含归属组别与 connector 列表，供上层记录 A/B 测试结果。</p>
 *
 * <p><b>统计</b>：内嵌 {@link AbTestMetrics} 实时聚合，可通过 {@link #metrics}
 * 查询对比指标。决策计数器 {@code aiDecisions} / {@code ruleDecisions} 用于
 * 测试验证流量分配比例。</p>
 */
public class AbTestRouter {

    private static final Logger log = LoggerFactory.getLogger(AbTestRouter.class);

    static final String METHOD_AI = "ai";
    static final String METHOD_RULE = "rule";

    private final AiRoutingStrategy aiStrategy;
    private final int aiTrafficPercentage;
    private final boolean enabled;
    private final AbTestMetrics metrics;

    private final AtomicLong aiDecisions = new AtomicLong();
    private final AtomicLong ruleDecisions = new AtomicLong();
    private final AtomicLong degradedToRule = new AtomicLong();

    public AbTestRouter(AiRoutingStrategy aiStrategy, int aiTrafficPercentage, boolean enabled) {
        this(aiStrategy, aiTrafficPercentage, enabled, new AbTestMetrics());
    }

    public AbTestRouter(AiRoutingStrategy aiStrategy, int aiTrafficPercentage,
                        boolean enabled, AbTestMetrics metrics) {
        if (aiTrafficPercentage < 0 || aiTrafficPercentage > 100) {
            throw new IllegalArgumentException(
                    "aiTrafficPercentage must be in [0,100], got " + aiTrafficPercentage);
        }
        this.aiStrategy = aiStrategy;
        this.aiTrafficPercentage = aiTrafficPercentage;
        this.enabled = enabled;
        this.metrics = metrics;
    }

    /**
     * 决定本次请求的路由方法并执行路由。
     *
     * @param aiCandidates    AI 路由候选 connector 列表
     * @param ruleResult      规则路由已解析的 connector 列表（作为规则组结果与降级兜底）
     * @param amount          当前支付金额
     * @param currency        当前支付币种
     * @return 路由决策（包含方法、connector 列表、是否降级）
     */
    public Decision decide(List<PaymentConnector> aiCandidates,
                           List<PaymentConnector> ruleResult,
                           long amount, String currency) {
        // A/B 测试未启用 -> 直接走规则组
        if (!enabled) {
            ruleDecisions.incrementAndGet();
            return new Decision(METHOD_RULE, ruleResult, false);
        }

        // 分流：按百分比投掷随机数
        boolean assignToAi = ThreadLocalRandom.current().nextInt(100) < aiTrafficPercentage;

        if (!assignToAi) {
            ruleDecisions.incrementAndGet();
            return new Decision(METHOD_RULE, ruleResult, false);
        }

        // 分流到 AI 组：尝试 AI 路由
        List<PaymentConnector> aiResult;
        try {
            aiResult = aiStrategy.resolve(aiCandidates, amount, currency);
        } catch (Exception e) {
            log.warn("AI routing threw exception, degrading to rule: {}", e.getMessage());
            aiResult = List.of();
        }

        if (aiResult == null || aiResult.isEmpty()) {
            // 降级到规则组
            aiDecisions.incrementAndGet();
            degradedToRule.incrementAndGet();
            log.debug("AI routing degraded to rule for currency={}, amount={}", currency, amount);
            return new Decision(METHOD_RULE, ruleResult, true);
        }

        aiDecisions.incrementAndGet();
        return new Decision(METHOD_AI, aiResult, false);
    }

    /**
     * 记入一条 A/B 测试最终结果（支付完成后回调）。
     */
    public void recordOutcome(AbTestResult result) {
        metrics.record(result);
    }

    public AbTestMetrics metrics() {
        return metrics;
    }

    public long aiDecisions() { return aiDecisions.get(); }
    public long ruleDecisions() { return ruleDecisions.get(); }
    public long degradedCount() { return degradedToRule.get(); }

    public boolean enabled() { return enabled; }
    public int aiTrafficPercentage() { return aiTrafficPercentage; }

    /**
     * 路由决策结果。
     *
     * @param method      实际使用的路由方法（"ai" / "rule"）
     * @param connectors  最终 connector 列表
     * @param degraded    是否从 AI 降级到规则
     */
    public record Decision(String method, List<PaymentConnector> connectors, boolean degraded) {

        public boolean isAi() { return METHOD_AI.equals(method); }
        public boolean isRule() { return METHOD_RULE.equals(method); }
    }
}