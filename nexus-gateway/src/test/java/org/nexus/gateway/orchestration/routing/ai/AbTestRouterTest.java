package org.nexus.gateway.orchestration.routing.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.PaymentConnector;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link AbTestRouter} 单元测试。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>流量分配比例：aiTrafficPercentage=0/50/100 时的分流</li>
 *   <li>降级逻辑：AI 路由返回空时降级到规则组</li>
 *   <li>降级逻辑：AI 路由抛异常时降级到规则组</li>
 *   <li>A/B 测试禁用时全部走规则组</li>
 *   <li>指标聚合：recordOutcome 后 metrics 正确</li>
 *   <li>决策计数器</li>
 * </ul>
 */
class AbTestRouterTest {

    private AiRoutingStrategy aiStrategy;
    private PaymentConnector c1;
    private PaymentConnector c2;
    private PaymentConnector c3;

    @BeforeEach
    void setUp() {
        aiStrategy = mock(AiRoutingStrategy.class);
        c1 = mockConnector("c1");
        c2 = mockConnector("c2");
        c3 = mockConnector("c3");
    }

    private PaymentConnector mockConnector(String id) {
        PaymentConnector c = mock(PaymentConnector.class);
        when(c.getId()).thenReturn(id);
        when(c.isActive()).thenReturn(true);
        return c;
    }

    // === 流量分配比例 ===

    @Test
    @DisplayName("decide: aiTrafficPercentage=0 时全部走规则组")
    void decide_zeroPercent_allRule() {
        AbTestRouter router = new AbTestRouter(aiStrategy, 0, true);
        List<PaymentConnector> ruleResult = List.of(c1, c2);
        when(aiStrategy.resolve(any(), anyLong(), any())).thenReturn(List.of(c2, c1));

        for (int i = 0; i < 100; i++) {
            AbTestRouter.Decision d = router.decide(ruleResult, ruleResult, 1000, "NEX");
            assertTrue(d.isRule());
        }
        assertEquals(0, router.aiDecisions());
        assertEquals(100, router.ruleDecisions());
    }

    @Test
    @DisplayName("decide: aiTrafficPercentage=100 时全部走 AI 组")
    void decide_fullPercent_allAi() {
        AbTestRouter router = new AbTestRouter(aiStrategy, 100, true);
        List<PaymentConnector> ruleResult = List.of(c1, c2);
        when(aiStrategy.resolve(any(), anyLong(), any())).thenReturn(List.of(c2, c1));

        for (int i = 0; i < 100; i++) {
            AbTestRouter.Decision d = router.decide(ruleResult, ruleResult, 1000, "NEX");
            assertTrue(d.isAi());
        }
        assertEquals(100, router.aiDecisions());
        assertEquals(0, router.ruleDecisions());
    }

    @Test
    @DisplayName("decide: aiTrafficPercentage=50 时约半数走 AI 组")
    void decide_halfPercent_approxHalfAi() {
        AbTestRouter router = new AbTestRouter(aiStrategy, 50, true);
        List<PaymentConnector> ruleResult = List.of(c1, c2);
        when(aiStrategy.resolve(any(), anyLong(), any())).thenReturn(List.of(c2, c1));

        int total = 1000;
        for (int i = 0; i < total; i++) {
            router.decide(ruleResult, ruleResult, 1000, "NEX");
        }
        // 允许统计波动 ±10%
        long ai = router.aiDecisions();
        assertTrue(ai > total * 0.4, "AI 决策数应 > 40%，实际 " + ai);
        assertTrue(ai < total * 0.6, "AI 决策数应 < 60%，实际 " + ai);
    }

    // === 降级逻辑 ===

    @Test
    @DisplayName("decide: AI 路由返回空 -> 降级到规则组")
    void decide_aiEmpty_degradesToRule() {
        AbTestRouter router = new AbTestRouter(aiStrategy, 100, true);
        List<PaymentConnector> ruleResult = List.of(c1, c2);
        when(aiStrategy.resolve(any(), anyLong(), any())).thenReturn(List.of());

        AbTestRouter.Decision d = router.decide(ruleResult, ruleResult, 1000, "NEX");
        assertTrue(d.isRule());
        assertTrue(d.degraded());
        assertEquals(1, router.degradedCount());
    }

    @Test
    @DisplayName("decide: AI 路由抛异常 -> 降级到规则组")
    void decide_aiThrows_degradesToRule() {
        AbTestRouter router = new AbTestRouter(aiStrategy, 100, true);
        List<PaymentConnector> ruleResult = List.of(c1, c2);
        when(aiStrategy.resolve(any(), anyLong(), any())).thenThrow(new RuntimeException("model down"));

        AbTestRouter.Decision d = router.decide(ruleResult, ruleResult, 1000, "NEX");
        assertTrue(d.isRule());
        assertTrue(d.degraded());
    }

    @Test
    @DisplayName("decide: AI 路由返回 null -> 降级到规则组")
    void decide_aiNull_degradesToRule() {
        AbTestRouter router = new AbTestRouter(aiStrategy, 100, true);
        List<PaymentConnector> ruleResult = List.of(c1, c2);
        when(aiStrategy.resolve(any(), anyLong(), any())).thenReturn(null);

        AbTestRouter.Decision d = router.decide(ruleResult, ruleResult, 1000, "NEX");
        assertTrue(d.isRule());
        assertTrue(d.degraded());
    }

    // === A/B 测试禁用 ===

    @Test
    @DisplayName("decide: enabled=false 时全部走规则组")
    void decide_disabled_allRule() {
        AbTestRouter router = new AbTestRouter(aiStrategy, 100, false);
        List<PaymentConnector> ruleResult = List.of(c1, c2);
        when(aiStrategy.resolve(any(), anyLong(), any())).thenReturn(List.of(c2, c1));

        for (int i = 0; i < 100; i++) {
            AbTestRouter.Decision d = router.decide(ruleResult, ruleResult, 1000, "NEX");
            assertTrue(d.isRule());
            assertFalse(d.degraded());
        }
        assertEquals(0, router.aiDecisions());
    }

    // === AI 路由结果 ===

    @Test
    @DisplayName("decide: AI 组返回模型排序后的列表")
    void decide_ai_returnsSorted() {
        AbTestRouter router = new AbTestRouter(aiStrategy, 100, true);
        List<PaymentConnector> ruleResult = List.of(c1, c2, c3);
        when(aiStrategy.resolve(any(), anyLong(), any())).thenReturn(List.of(c3, c1, c2));

        AbTestRouter.Decision d = router.decide(ruleResult, ruleResult, 1000, "NEX");
        assertTrue(d.isAi());
        assertEquals(3, d.connectors().size());
        assertEquals("c3", d.connectors().get(0).getId());
        assertEquals("c1", d.connectors().get(1).getId());
        assertEquals("c2", d.connectors().get(2).getId());
    }

    // === 指标聚合 ===

    @Test
    @DisplayName("recordOutcome: AI 组成功/失败正确聚合")
    void recordOutcome_aiGroup() {
        AbTestRouter router = new AbTestRouter(aiStrategy, 100, true);

        router.recordOutcome(new AbTestResult("ai", "c1", AbTestResult.Outcome.SUCCESS, 100, java.time.Instant.now()));
        router.recordOutcome(new AbTestResult("ai", "c1", AbTestResult.Outcome.SUCCESS, 200, java.time.Instant.now()));
        router.recordOutcome(new AbTestResult("ai", "c1", AbTestResult.Outcome.FAILURE, 300, java.time.Instant.now()));

        AbTestMetrics.GroupStats ai = router.metrics().aiGroup();
        assertEquals(3, ai.total());
        assertEquals(2, ai.success());
        assertEquals(1, ai.failure());
        assertEquals(2.0 / 3, ai.successRate(), 0.001);
        assertEquals(200, ai.avgLatencyMs()); // (100+200+300)/3
    }

    @Test
    @DisplayName("recordOutcome: PENDING 状态不聚合")
    void recordOutcome_pendingIgnored() {
        AbTestRouter router = new AbTestRouter(aiStrategy, 100, true);
        router.recordOutcome(AbTestResult.pending("ai", "c1"));
        assertEquals(0, router.metrics().aiGroup().total());
    }

    @Test
    @DisplayName("comparison: AI 组成功率优于规则组时 successRateDiff > 0")
    void comparison_aiWins() {
        AbTestRouter router = new AbTestRouter(aiStrategy, 100, true);

        // AI 组：3 成功 1 失败 -> 75%
        router.recordOutcome(new AbTestResult("ai", "c1", AbTestResult.Outcome.SUCCESS, 100, java.time.Instant.now()));
        router.recordOutcome(new AbTestResult("ai", "c1", AbTestResult.Outcome.SUCCESS, 100, java.time.Instant.now()));
        router.recordOutcome(new AbTestResult("ai", "c1", AbTestResult.Outcome.SUCCESS, 100, java.time.Instant.now()));
        router.recordOutcome(new AbTestResult("ai", "c1", AbTestResult.Outcome.FAILURE, 100, java.time.Instant.now()));

        // 规则组：1 成功 3 失败 -> 25%
        router.recordOutcome(new AbTestResult("rule", "c1", AbTestResult.Outcome.SUCCESS, 200, java.time.Instant.now()));
        router.recordOutcome(new AbTestResult("rule", "c1", AbTestResult.Outcome.FAILURE, 200, java.time.Instant.now()));
        router.recordOutcome(new AbTestResult("rule", "c1", AbTestResult.Outcome.FAILURE, 200, java.time.Instant.now()));
        router.recordOutcome(new AbTestResult("rule", "c1", AbTestResult.Outcome.FAILURE, 200, java.time.Instant.now()));

        AbTestMetrics.Comparison cmp = router.metrics().comparison();
        assertTrue(cmp.successRateDiff() > 0, "AI 组成功率应高于规则组");
        assertTrue(cmp.aiWinsOnSuccessRate());
        assertTrue(cmp.avgLatencyDiff() < 0, "AI 组延迟应低于规则组");
    }

    // === 边界 ===

    @Test
    @DisplayName("构造: aiTrafficPercentage 越界抛异常")
    void constructor_invalidPercentage() {
        assertThrows(IllegalArgumentException.class, () -> new AbTestRouter(aiStrategy, -1, true));
        assertThrows(IllegalArgumentException.class, () -> new AbTestRouter(aiStrategy, 101, true));
    }
}