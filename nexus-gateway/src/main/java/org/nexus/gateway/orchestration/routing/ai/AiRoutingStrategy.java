package org.nexus.gateway.orchestration.routing.ai;

import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 路由策略：基于历史指标 + 路由模型对候选 connector 排序。
 *
 * <p><b>职责</b>：</p>
 * <ol>
 *   <li>从 {@link MetricsCollector} 获取每个候选 connector 的历史指标</li>
 *   <li>构造 {@link ModelFeatures}（融合指标 + 当前支付上下文）</li>
 *   <li>调用 {@link RoutingModel#predict} 得到排序后的 connectorId 列表</li>
 *   <li>映射回 {@link PaymentConnector} 列表（跳过未注册/非 active 的）</li>
 * </ol>
 *
 * <p><b>降级策略</b>：当模型抛异常、或所有候选样本数不足 {@code minSamples}、
 * 或模型返回空列表时，{@link #resolve} 返回空列表，由 {@code RoutingEngine}
 * 上层降级到规则路由。本类不抛异常，避免阻塞支付主链路。</p>
 *
 * <p><b>性能</b>：单次推理 = 指标查询（O(候选数)）+ 模型 predict（&lt; 50ms），
 * 总延迟 &lt; 50ms（候选数通常 &lt; 10）。</p>
 */

public class AiRoutingStrategy {

    private static final Logger log = LoggerFactory.getLogger(AiRoutingStrategy.class);

    private final MetricsCollector metricsCollector;
    private final RoutingModel model;
    private final int minSamples;

    public AiRoutingStrategy(MetricsCollector metricsCollector, RoutingModel model) {
        this(metricsCollector, model, 10);
    }

    public AiRoutingStrategy(MetricsCollector metricsCollector, RoutingModel model, int minSamples) {
        this.metricsCollector = metricsCollector;
        this.model = model;
        this.minSamples = minSamples;
    }

    /**
     * 基于 AI 模型对候选 connector 排序。
     *
     * @param candidates 候选 connector 列表（已过滤 active，非空）
     * @param amount     当前支付金额（最小单位）
     * @param currency   当前支付币种
     * @return 按模型打分降序排列的 connector 列表；无法推理时返回空列表（降级）
     */
    public List<PaymentConnector> resolve(List<PaymentConnector> candidates,
                                          long amount, String currency) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (candidates.size() == 1) {
            return List.of(candidates.get(0));
        }

        // 1. 构造特征向量
        List<ModelFeatures> features = new ArrayList<>(candidates.size());
        boolean anyEnough = false;
        for (PaymentConnector c : candidates) {
            ConnectorMetrics m = metricsCollector.metrics(c.getId());
            if (m.hasEnoughSamples(minSamples)) {
                anyEnough = true;
            }
            features.add(new ModelFeatures(
                    c.getId(),
                    m.successRate(),
                    m.avgLatencyMs(),
                    m.avgCostBps() > 0 ? m.avgCostBps() : c.feeBasisPoints(),
                    m.recentFailures(),
                    amount,
                    currency));
        }

        // 2. 样本数全部不足 -> 降级（冷启动）
        if (!anyEnough) {
            log.debug("AI routing degraded: no connector has enough samples (min={})", minSamples);
            return List.of();
        }

        // 3. 模型推理
        List<String> ordered;
        try {
            ordered = model.predict(features);
        } catch (RuntimeException e) {
            log.warn("AI routing model predict failed, degrading: {}", e.getMessage());
            return List.of();
        }
        if (ordered == null || ordered.isEmpty()) {
            log.debug("AI routing model returned empty list, degrading");
            return List.of();
        }

        // 4. 映射回 PaymentConnector（保持模型顺序，跳过未匹配的）
        Map<String, PaymentConnector> byId = new HashMap<>(candidates.size() * 2);
        for (PaymentConnector c : candidates) {
            byId.put(c.getId(), c);
        }
        List<PaymentConnector> result = new ArrayList<>(ordered.size());
        for (String id : ordered) {
            PaymentConnector c = byId.get(id);
            if (c != null) {
                result.add(c);
            }
        }
        // 兜底：模型返回的 id 全部未匹配（不应发生），返回原始候选
        if (result.isEmpty()) {
            log.warn("AI routing model returned unmatched ids {}, falling back to candidates", ordered);
            return candidates;
        }
        return result;
    }

    /** 模型类型标识（暴露给上层用于 span/日志）。 */
    public String modelType() {
        return model.modelType();
    }
}