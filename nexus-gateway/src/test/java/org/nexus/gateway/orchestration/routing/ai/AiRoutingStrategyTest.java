package org.nexus.gateway.orchestration.routing.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.PaymentConnector;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link AiRoutingStrategy} 集成测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>正常路由：有足够样本时调用模型返回排序结果</li>
 *   <li>冷启动降级：所有候选样本不足时返回空列表</li>
 *   <li>部分样本不足：只要有任一候选足够即不降级</li>
 *   <li>模型异常降级：模型抛异常时返回空列表</li>
 *   <li>模型返回空降级</li>
 *   <li>单候选直接返回</li>
 *   <li>特征构造：avgCostBps 为 0 时回退到 connector.feeBasisPoints</li>
 *   <li>推理延迟 &lt; 50ms</li>
 * </ul>
 */
class AiRoutingStrategyTest {

    private MetricsCollector metricsCollector;
    private RoutingModel model;
    private PaymentConnector c1;
    private PaymentConnector c2;
    private PaymentConnector c3;

    @BeforeEach
    void setUp() {
        metricsCollector = new MetricsCollector(100);
        model = mock(RoutingModel.class);
        when(model.modelType()).thenReturn("heuristic");
        c1 = mockConnector("c1", 10);
        c2 = mockConnector("c2", 20);
        c3 = mockConnector("c3", 30);
    }

    private PaymentConnector mockConnector(String id, int feeBps) {
        PaymentConnector c = mock(PaymentConnector.class);
        when(c.getId()).thenReturn(id);
        when(c.isActive()).thenReturn(true);
        when(c.feeBasisPoints()).thenReturn(feeBps);
        return c;
    }

    private void feedSamples(String connectorId, int successCount, int failCount, long latency, int cost) {
        for (int i = 0; i < successCount; i++) {
            metricsCollector.record(connectorId, true, latency, cost);
        }
        for (int i = 0; i < failCount; i++) {
            metricsCollector.record(connectorId, false, latency, cost);
        }
    }

    // === 正常路由 ===

    @Test
    @DisplayName("resolve: 有足够样本时调用模型返回排序结果")
    void resolve_enoughSamples_returnsModelResult() {
        feedSamples("c1", 10, 0, 100, 10);
        feedSamples("c2", 10, 0, 200, 20);
        feedSamples("c3", 10, 0, 300, 30);

        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, model, 10);
        when(model.predict(any())).thenReturn(List.of("c3", "c1", "c2"));

        List<PaymentConnector> result = strategy.resolve(List.of(c1, c2, c3), 1000, "NEX");
        assertEquals(3, result.size());
        assertEquals("c3", result.get(0).getId());
        assertEquals("c1", result.get(1).getId());
        assertEquals("c2", result.get(2).getId());
    }

    // === 冷启动降级 ===

    @Test
    @DisplayName("resolve: 所有候选样本不足 -> 返回空列表（降级）")
    void resolve_coldStart_degrades() {
        // 无任何样本
        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, model, 10);
        List<PaymentConnector> result = strategy.resolve(List.of(c1, c2), 1000, "NEX");
        assertTrue(result.isEmpty());
        verify(model, never()).predict(any());
    }

    @Test
    @DisplayName("resolve: 部分候选样本足够 -> 不降级")
    void resolve_partialSamples_noDegrade() {
        feedSamples("c1", 10, 0, 100, 10); // c1 足够
        // c2 无样本
        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, model, 10);
        when(model.predict(any())).thenReturn(List.of("c1", "c2"));

        List<PaymentConnector> result = strategy.resolve(List.of(c1, c2), 1000, "NEX");
        assertEquals(2, result.size());
        assertEquals("c1", result.get(0).getId());
    }

    // === 模型异常降级 ===

    @Test
    @DisplayName("resolve: 模型抛异常 -> 返回空列表（降级）")
    void resolve_modelThrows_degrades() {
        feedSamples("c1", 10, 0, 100, 10);
        feedSamples("c2", 10, 0, 200, 20);
        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, model, 10);
        when(model.predict(any())).thenThrow(new RuntimeException("model error"));

        List<PaymentConnector> result = strategy.resolve(List.of(c1, c2), 1000, "NEX");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("resolve: 模型返回空 -> 返回空列表（降级）")
    void resolve_modelEmpty_degrades() {
        feedSamples("c1", 10, 0, 100, 10);
        feedSamples("c2", 10, 0, 200, 20);
        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, model, 10);
        when(model.predict(any())).thenReturn(List.of());

        List<PaymentConnector> result = strategy.resolve(List.of(c1, c2), 1000, "NEX");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("resolve: 模型返回 null -> 返回空列表（降级）")
    void resolve_modelNull_degrades() {
        feedSamples("c1", 10, 0, 100, 10);
        feedSamples("c2", 10, 0, 200, 20);
        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, model, 10);
        when(model.predict(any())).thenReturn(null);

        List<PaymentConnector> result = strategy.resolve(List.of(c1, c2), 1000, "NEX");
        assertTrue(result.isEmpty());
    }

    // === 单候选 ===

    @Test
    @DisplayName("resolve: 单候选直接返回（不调用模型）")
    void resolve_singleCandidate_shortCircuit() {
        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, model, 10);
        List<PaymentConnector> result = strategy.resolve(List.of(c1), 1000, "NEX");
        assertEquals(1, result.size());
        assertEquals("c1", result.get(0).getId());
        verify(model, never()).predict(any());
    }

    // === 空候选 ===

    @Test
    @DisplayName("resolve: 空候选返回空列表")
    void resolve_emptyCandidates() {
        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, model, 10);
        assertTrue(strategy.resolve(List.of(), 1000, "NEX").isEmpty());
        assertTrue(strategy.resolve(null, 1000, "NEX").isEmpty());
    }

    // === 特征构造 ===

    @Test
    @DisplayName("resolve: avgCostBps=0 时特征 cost 回退到 connector.feeBasisPoints")
    void resolve_costFallbackToFeeBps() {
        // c1 有样本但 cost=0，应回退到 feeBasisPoints
        feedSamples("c1", 10, 0, 100, 0);
        feedSamples("c2", 10, 0, 200, 0);

        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, model, 10);
        when(model.predict(any())).thenAnswer(invocation -> {
            List<ModelFeatures> features = invocation.getArgument(0);
            // 验证特征中 cost 为 feeBasisPoints
            ModelFeatures f1 = features.stream().filter(f -> f.connectorId().equals("c1")).findFirst().orElseThrow();
            ModelFeatures f2 = features.stream().filter(f -> f.connectorId().equals("c2")).findFirst().orElseThrow();
            assertEquals(10, f1.avgCostBps()); // c1.feeBasisPoints
            assertEquals(20, f2.avgCostBps()); // c2.feeBasisPoints
            return List.of("c1", "c2");
        });

        List<PaymentConnector> result = strategy.resolve(List.of(c1, c2), 1000, "NEX");
        assertEquals(2, result.size());
    }

    // === 模型返回未匹配 id ===

    @Test
    @DisplayName("resolve: 模型返回未匹配 id -> 返回原始候选（兜底）")
    void resolve_unmatchedIds_fallbackToCandidates() {
        feedSamples("c1", 10, 0, 100, 10);
        feedSamples("c2", 10, 0, 200, 20);
        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, model, 10);
        when(model.predict(any())).thenReturn(List.of("ghost1", "ghost2"));

        List<PaymentConnector> result = strategy.resolve(List.of(c1, c2), 1000, "NEX");
        // 兜底返回原始候选
        assertEquals(2, result.size());
    }

    // === modelType ===

    @Test
    @DisplayName("modelType: 返回底层模型的类型")
    void modelType() {
        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, model, 10);
        assertEquals("heuristic", strategy.modelType());
    }

    // === 推理延迟 < 50ms ===

    @Test
    @DisplayName("性能: 推理延迟 < 50ms（含真实 HeuristicRoutingModel）")
    void inferenceLatency_under50ms() {
        // 使用真实模型而非 mock，验证端到端延迟
        HeuristicRoutingModel realModel = new HeuristicRoutingModel();
        AiRoutingStrategy strategy = new AiRoutingStrategy(metricsCollector, realModel, 10);

        // 喂样本
        for (int i = 0; i < 10; i++) {
            metricsCollector.record("c1", true, 100, 10);
            metricsCollector.record("c2", true, 200, 20);
            metricsCollector.record("c3", true, 300, 30);
        }

        List<PaymentConnector> candidates = List.of(c1, c2, c3);
        // 预热
        strategy.resolve(candidates, 1000, "NEX");

        // 测量 10 次取最大值
        long maxLatency = 0;
        for (int i = 0; i < 10; i++) {
            long start = System.nanoTime();
            strategy.resolve(candidates, 1000, "NEX");
            long elapsed = System.nanoTime() - start;
            maxLatency = Math.max(maxLatency, elapsed);
        }
        long maxLatencyMs = maxLatency / 1_000_000;
        assertTrue(maxLatencyMs < 50,
                "推理延迟应 < 50ms，实际最大 " + maxLatencyMs + "ms");
    }
}