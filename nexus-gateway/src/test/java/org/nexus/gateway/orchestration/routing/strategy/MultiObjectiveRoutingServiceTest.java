package org.nexus.gateway.orchestration.routing.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.RoutingWave16Properties;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.routing.RoutingContext;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link MultiObjectiveRoutingService} 单元测试：权重解析/归一化、条件匹配、
 * 评分排序、画像修正、热加载与 CRUD 校验。
 */
class MultiObjectiveRoutingServiceTest {

    private RoutingStrategyConfigRepository repository;
    private RoutingWave16Properties properties;
    private MultiObjectiveRoutingService service;

    private static final Long MERCHANT_ID = 42L;

    @BeforeEach
    void setUp() {
        repository = mock(RoutingStrategyConfigRepository.class);
        properties = new RoutingWave16Properties();
        service = new MultiObjectiveRoutingService(repository, properties);
    }

    private PaymentConnector connector(String id, int fee) {
        PaymentConnector c = mock(PaymentConnector.class);
        when(c.getId()).thenReturn(id);
        when(c.feeBasisPoints()).thenReturn(fee);
        return c;
    }

    // ==================== 权重解析 ====================

    @Test
    @DisplayName("parseWeights: 完整四键权重归一化到 sum=1")
    void parseWeightsFull() {
        MultiObjectiveRoutingService.ObjectiveWeights w =
                service.parseWeights("{\"COST\":1,\"SUCCESS_RATE\":1,\"LATENCY\":1,\"RISK\":1}");
        assertEquals(0.25, w.cost(), 1e-9);
        assertEquals(0.25, w.successRate(), 1e-9);
        assertEquals(0.25, w.latency(), 1e-9);
        assertEquals(0.25, w.risk(), 1e-9);
    }

    @Test
    @DisplayName("parseWeights: 缺失键回退默认权重；非法 JSON 返回 null")
    void parseWeightsPartialAndInvalid() {
        MultiObjectiveRoutingService.ObjectiveWeights w = service.parseWeights("{\"COST\":2}");
        // COST=2 其余取默认（0.25*3），sum = 2.75 → cost ≈ 0.727
        assertEquals(2.0 / 2.75, w.cost(), 1e-9);

        assertNull(service.parseWeights("not-json"));
        assertNull(service.parseWeights(null));
        assertNull(service.parseWeights("{\"UNKNOWN\":1}"));
    }

    @Test
    @DisplayName("resolveWeights: 无 DB 配置时返回属性默认权重")
    void resolveWeightsDefaults() {
        when(repository.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of());
        MultiObjectiveRoutingService.ObjectiveWeights w = service.resolveWeights(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(1000), "NEX"));
        assertEquals(0.25, w.cost(), 1e-9);
        assertEquals(0.35, w.successRate(), 1e-9);
        assertEquals(0.25, w.latency(), 1e-9);
        assertEquals(0.15, w.risk(), 1e-9);
    }

    @Test
    @DisplayName("resolveWeights: priority 降序第一条条件命中的配置生效")
    void resolveWeightsPriorityAndConditions() {
        RoutingStrategyConfig low = config("low", 1, "{\"COST\":1,\"SUCCESS_RATE\":1,\"LATENCY\":1,\"RISK\":1}",
                "{\"currency\":\"NEX\",\"amount_gte\":\"10000\"}");
        RoutingStrategyConfig high = config("high", 9, "{\"COST\":4,\"SUCCESS_RATE\":4,\"LATENCY\":1,\"RISK\":1}",
                "{\"currency\":\"NEX\"}");
        when(repository.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(high, low));

        // 大额命中 high
        MultiObjectiveRoutingService.ObjectiveWeights big = service.resolveWeights(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(20000), "NEX"));
        assertEquals(0.4, big.cost(), 1e-9);
        // 非 NEX 币种不命中任何配置 → 默认
        MultiObjectiveRoutingService.ObjectiveWeights other = service.resolveWeights(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(20000), "USD"));
        assertEquals(0.25, other.cost(), 1e-9);
    }

    private RoutingStrategyConfig config(String name, int priority, String weights, String conditions) {
        RoutingStrategyConfig config = new RoutingStrategyConfig();
        config.setId(1L);
        config.setName(name);
        config.setPriority(priority);
        config.setEnabled(true);
        config.setObjectiveWeightsJson(weights);
        config.setConditionsJson(conditions);
        return config;
    }

    // ==================== 决策 ====================

    @Test
    @DisplayName("decide: 低费率且高成功率候选排前；禁用时保持原顺序")
    void decideOrderingAndBypass() {
        when(repository.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of());
        PaymentConnector cheap = connector("cheap", 10);
        PaymentConnector pricey = connector("pricey", 200);
        List<PaymentConnector> candidates = List.of(pricey, cheap);

        MultiObjectiveRoutingService.MultiObjectiveDecision decision = service.decide(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(100), "NEX"), candidates);
        assertEquals("cheap", decision.orderedIds().get(0));
        assertTrue(decision.scores().get("cheap") > decision.scores().get("pricey"));

        // 策略禁用 → 原顺序（Wave 15 等价）
        properties.getStrategy().setEnabled(false);
        MultiObjectiveRoutingService.MultiObjectiveDecision bypass = service.decide(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(100), "NEX"), candidates);
        assertEquals(List.of("pricey", "cheap"), bypass.orderedIds());
        assertTrue(bypass.scores().isEmpty());
    }

    @Test
    @DisplayName("decide: 画像排除 connector，偏好 connector 获得加成")
    void decideProfileAdjustments() {
        when(repository.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of());
        PaymentConnector a = connector("a", 100);
        PaymentConnector b = connector("b", 100);
        org.nexus.gateway.orchestration.routing.profile.ResolvedRoutingProfile profile =
                new org.nexus.gateway.orchestration.routing.profile.ResolvedRoutingProfile(
                        "p1", MERCHANT_ID, null, "MEDIUM", false,
                        List.of(), List.of("b"), List.of("a"));
        RoutingContext ctx = RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(100), "NEX")
                .withProfile(profile);

        MultiObjectiveRoutingService.MultiObjectiveDecision decision =
                service.decide(ctx, List.of(a, b));
        // a 被排除 → 仅剩 b
        assertEquals(List.of("b"), decision.orderedIds());

        // 仅偏好（不排除）→ b 加成居首
        org.nexus.gateway.orchestration.routing.profile.ResolvedRoutingProfile preferOnly =
                new org.nexus.gateway.orchestration.routing.profile.ResolvedRoutingProfile(
                        "p1", MERCHANT_ID, null, "MEDIUM", false,
                        List.of(), List.of("b"), List.of());
        MultiObjectiveRoutingService.MultiObjectiveDecision prefer = service.decide(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(100), "NEX").withProfile(preferOnly),
                List.of(a, b));
        assertEquals("b", prefer.orderedIds().get(0));
    }

    // ==================== CRUD ====================

    @Test
    @DisplayName("create: 名称重复/权重非法被拒绝；合法配置触发缓存刷新")
    void createValidation() {
        when(repository.findByName("dup")).thenReturn(Optional.of(config("dup", 0, "{\"COST\":1}", null)));
        when(repository.findByName("ok")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RoutingStrategyConfig dup = config("dup", 0, "{\"COST\":1}", null);
        assertThrows(IllegalArgumentException.class, () -> service.create(dup, "op"));

        RoutingStrategyConfig badWeights = config("bad", 0, "{invalid", null);
        assertThrows(IllegalArgumentException.class, () -> service.create(badWeights, "op"));

        RoutingStrategyConfig ok = config("ok", 0, "{\"COST\":1,\"SUCCESS_RATE\":1,\"LATENCY\":1,\"RISK\":1}", null);
        RoutingStrategyConfig saved = service.create(ok, "op");
        assertEquals("ok", saved.getName());
        verify(repository).save(any());
    }

    // ==================== 规则级固定权重（2026-10-03 A4） ====================

    @Test
    @DisplayName("pinned: 规则钉死的配置权重压过条件链——COST 独大时低费率候选必胜")
    void pinnedConfigOverridesChain() {
        // 条件链里放一个高优先级、SUCCESS_RATE 独大的配置（若走链，高成功率者胜）
        when(repository.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(
                config("chain", 100, "{\"SUCCESS_RATE\":1000}", null)));
        // 钉死配置：COST 独大（若走 pinned，低费率者胜）
        when(repository.findById(9L)).thenReturn(Optional.of(
                config("pinned", 1, "{\"COST\":1000}", null)));

        PaymentConnector cheap = connector("cheap", 1);
        PaymentConnector expensive = connector("expensive", 5000);

        // 无 pinned → 链生效（SUCCESS_RATE 独大：无指标时两者打平，仅验证走链路径不炸）
        MultiObjectiveRoutingService.MultiObjectiveDecision chainDecision = service.decide(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(100), "NEX"),
                List.of(cheap, expensive), null);
        assertEquals(2, chainDecision.orderedIds().size());

        // pinned → COST 独大：cheap 必胜
        MultiObjectiveRoutingService.MultiObjectiveDecision pinnedDecision = service.decide(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(100), "NEX"),
                List.of(expensive, cheap), 9L);
        assertEquals("cheap", pinnedDecision.orderedIds().get(0));
        // 验证 pinned 生效：cheap 分数显著高于 expensive
        assertTrue(pinnedDecision.scores().get("cheap") > pinnedDecision.scores().get("expensive") + 0.5);
    }

    @Test
    @DisplayName("pinned: 配置缺失/禁用/权重非法 → 告警降级回条件链（不炸路由）")
    void pinnedUnusableFallsBackToChain() {
        // 链上有一个 LATENCY 独大的高优先级配置
        when(repository.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(
                config("chain", 100, "{\"LATENCY\":1000}", null)));
        // 钉死 id=404 不存在
        when(repository.findById(404L)).thenReturn(Optional.empty());

        MultiObjectiveRoutingService.ObjectiveWeights weights404 = service.resolveWeights(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(100), "NEX"), 404L);
        // 降级到链 → LATENCY=1000（其余键取默认）归一后 ≈0.99925，绝对主导
        assertEquals(1000.0 / 1000.75, weights404.latency(), 1e-9);

        // 钉死到禁用配置 → 同样降级
        RoutingStrategyConfig disabled = config("off", 1, "{\"COST\":1000}", null);
        disabled.setEnabled(false);
        when(repository.findById(405L)).thenReturn(Optional.of(disabled));
        MultiObjectiveRoutingService.ObjectiveWeights weights405 = service.resolveWeights(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(100), "NEX"), 405L);
        assertEquals(1000.0 / 1000.75, weights405.latency(), 1e-9);
    }

    @Test
    @DisplayName("pinned: CRUD evictCache 同步清钉死缓存——改权重后规则引用立即生效")
    void evictClearsPinnedCache() {
        when(repository.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of());
        when(repository.findById(9L)).thenReturn(Optional.of(
                config("pinned", 1, "{\"COST\":1000}", null)));
        PaymentConnector cheap = connector("cheap", 1);
        PaymentConnector expensive = connector("expensive", 5000);

        MultiObjectiveRoutingService.MultiObjectiveDecision before = service.decide(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(100), "NEX"),
                List.of(expensive, cheap), 9L);
        assertEquals("cheap", before.orderedIds().get(0));

        // 权重翻转：改为 SUCCESS_RATE 独大（无指标时打平，cost 不再主导）
        when(repository.findById(9L)).thenReturn(Optional.of(
                config("pinned", 1, "{\"SUCCESS_RATE\":1000}", null)));
        service.evictCache();
        MultiObjectiveRoutingService.MultiObjectiveDecision after = service.decide(
                RoutingContext.simple(MERCHANT_ID, BigDecimal.valueOf(100), "NEX"),
                List.of(expensive, cheap), 9L);
        // SUCCESS_RATE 独大 + 无指标数据 → cost 维度仍按 feeBps 兜底归一；
        // 断言翻转后 expensive 不再因 cost 惨败（分数差 < 0.5）——验证缓存确实被清
        assertTrue(Math.abs(after.scores().get("cheap") - after.scores().get("expensive")) < 0.5,
                "evictCache 后 pinned 权重未生效");
    }
}
