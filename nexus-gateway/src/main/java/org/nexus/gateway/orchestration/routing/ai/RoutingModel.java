package org.nexus.gateway.orchestration.routing.ai;

import java.util.List;

/**
 * 路由模型接口：基于特征向量对候选 connector 打分排序。
 *
 * <p>实现可以是启发式模型（{@link HeuristicRoutingModel}）、离线训练的线性模型、
 * 或外部 ML 服务客户端。所有实现必须保证 {@link #predict} 延迟 &lt; 50ms，
 * 以避免阻塞支付主链路。</p>
 *
 * <p><b>契约</b>：</p>
 * <ul>
 *   <li>输入：候选 connector 的特征向量列表（非空）</li>
 *   <li>输出：按模型打分降序排列的 connectorId 列表（最优在前）</li>
 *   <li>异常：实现应吞掉内部异常并返回原始顺序，避免阻塞路由；如需降级由
 *       {@link AiRoutingStrategy} 处理</li>
 * </ul>
 */
public interface RoutingModel {

    /**
     * 对候选 connector 打分排序。
     *
     * @param features 候选 connector 的特征向量列表（非空）
     * @return 按打分降序排列的 connectorId 列表（最优在前）
     */
    List<String> predict(List<ModelFeatures> features);

    /** 模型类型标识（如 "heuristic"、"linear"、"external"）。 */
    String modelType();
}