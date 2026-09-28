package org.nexus.gateway.orchestration.routing;

import org.nexus.gateway.orchestration.connector.PaymentConnector;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 路由决策结果（Wave 16）— {@link RoutingEngine#resolveDetailed} 的返回值。
 *
 * <p>除有序 connector 列表外，携带审计所需的决策元数据：命中规则、策略、
 * 候选与评分明细、A/B 实验归属、审计记录 id。</p>
 *
 * @param connectors   有序 connector 列表（按优先尝试顺序）
 * @param ruleMatched  命中的规则 id（无命中时为 {@code null}；explicit 路由为 {@code "explicit"}）
 * @param strategy     策略名（PRIORITY/WEIGHT/COST/EXPLICIT/MULTI_OBJECTIVE/FALLBACK）
 * @param candidateIds 规则解析出的候选 id 列表
 * @param scores       多目标评分明细（connectorId → 总分；非多目标策略为空 map）
 * @param experimentId A/B 实验 id（未参与实验为 null）
 * @param abTestGroup  A/B 实验组（未参与实验为 null）
 * @param decisionId   审计记录 id（未落审计为 null）
 */
public record RoutingDecision(
        List<PaymentConnector> connectors,
        String ruleMatched,
        String strategy,
        List<String> candidateIds,
        LinkedHashMap<String, Double> scores,
        String experimentId,
        String abTestGroup,
        String decisionId) {
}
