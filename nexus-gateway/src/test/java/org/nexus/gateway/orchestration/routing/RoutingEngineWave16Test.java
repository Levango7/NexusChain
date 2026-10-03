package org.nexus.gateway.orchestration.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.config.RoutingWave16Properties;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.nexus.gateway.orchestration.routing.audit.RoutingAuditService;
import org.nexus.gateway.orchestration.routing.audit.RoutingDecisionRecord;
import org.nexus.gateway.orchestration.routing.audit.RoutingDecisionRecordRepository;
import org.nexus.gateway.orchestration.routing.experiment.RoutingExperimentService;
import org.nexus.gateway.orchestration.routing.experiment.RoutingExperimentRepository;
import org.nexus.gateway.orchestration.routing.strategy.MultiObjectiveRoutingService;
import org.nexus.gateway.orchestration.routing.strategy.RoutingStrategyConfigRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link RoutingEngine} Wave 16 集成单元测试：MULTI_OBJECTIVE 接入、
 * Wave 15 等价性（协作方缺省）、决策审计接线、outcome 回填。
 */
class RoutingEngineWave16Test {

    private ConnectorRegistry registry;
    private GatewayConfig cfg;
    private RoutingWave16Properties properties;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectorRegistry.class);
        cfg = new GatewayConfig();
        properties = new RoutingWave16Properties();
    }

    private PaymentConnector connector(String id, boolean active, int fee) {
        PaymentConnector c = mock(PaymentConnector.class);
        when(c.getId()).thenReturn(id);
        when(c.isActive()).thenReturn(active);
        when(c.feeBasisPoints()).thenReturn(fee);
        return c;
    }

    private void mockRule(String ruleId, String strategy, String... connectorIds) {
        // 通过 DB 仓库恢复路径注入规则（ruleRepository 有数据时以 DB 为准）
    }

    @Test
    @DisplayName("等价性: Wave 16 协作方全部缺省时，resolve 行为与 Wave 15 一致")
    void legacyEquivalenceWithoutWave16Services() {
        PaymentConnector chain = connector("chain", true, 5);
        PaymentConnector mock = connector("mock", true, 0);
        when(registry.get("chain")).thenReturn(Optional.of(chain));
        when(registry.get("mock")).thenReturn(Optional.of(mock));

        RoutingEngine engine = new RoutingEngine(registry, cfg);
        List<PaymentConnector> result = engine.resolve("NEX", 1000, null);
        assertEquals("chain", result.get(0).getId());

        List<PaymentConnector> explicit = engine.resolve("NEX", 1000, "chain");
        assertEquals(1, explicit.size());
        assertEquals("chain", explicit.get(0).getId());
    }

    @Test
    @DisplayName("MULTI_OBJECTIVE 规则: 评分降序生效（低费率优先）")
    void multiObjectiveOrdering() {
        RoutingStrategyConfigRepository strategyRepo = mock(RoutingStrategyConfigRepository.class);
        when(strategyRepo.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of());
        MultiObjectiveRoutingService multiObjective =
                new MultiObjectiveRoutingService(strategyRepo, properties);

        PaymentConnector cheap = connector("cheap", true, 10);
        PaymentConnector pricey = connector("pricey", true, 200);
        when(registry.get("cheap")).thenReturn(Optional.of(cheap));
        when(registry.get("pricey")).thenReturn(Optional.of(pricey));

        RoutingEngine engine = new RoutingEngine(registry, cfg, null, null,
                multiObjective, null, null, null, null, null, properties);
        engine.addRule(new RoutingRule("multi", "multi rule", java.util.Map.of("currency", "USD"),
                RoutingStrategy.MULTI_OBJECTIVE, List.of("pricey", "cheap"), 20));

        List<PaymentConnector> result = engine.resolve("USD", 1000, null);
        assertEquals("cheap", result.get(0).getId());
    }

    @Test
    @DisplayName("MULTI_OBJECTIVE 规则: strategy_config_id 钉死权重——规则引用优先于全局链")
    void multiObjectivePinnedConfigOverrides() {
        RoutingStrategyConfigRepository strategyRepo = mock(RoutingStrategyConfigRepository.class);
        // 全局链放一个 SUCCESS_RATE 独大的高优先级配置（若走链，无指标时打平不重排）
        org.nexus.gateway.orchestration.routing.strategy.RoutingStrategyConfig chainConfig =
                new org.nexus.gateway.orchestration.routing.strategy.RoutingStrategyConfig();
        chainConfig.setId(1L);
        chainConfig.setName("chain");
        chainConfig.setEnabled(true);
        chainConfig.setObjectiveWeightsJson("{\"SUCCESS_RATE\":1000}");
        chainConfig.setPriority(100);
        when(strategyRepo.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of(chainConfig));
        // 规则钉死 id=9：COST 独大（低费率必胜）
        org.nexus.gateway.orchestration.routing.strategy.RoutingStrategyConfig pinned =
                new org.nexus.gateway.orchestration.routing.strategy.RoutingStrategyConfig();
        pinned.setId(9L);
        pinned.setName("pinned");
        pinned.setEnabled(true);
        pinned.setObjectiveWeightsJson("{\"COST\":1000}");
        when(strategyRepo.findById(9L)).thenReturn(Optional.of(pinned));
        MultiObjectiveRoutingService multiObjective =
                new MultiObjectiveRoutingService(strategyRepo, properties);

        PaymentConnector cheap = connector("cheap", true, 1);
        PaymentConnector pricey = connector("pricey", true, 5000);
        when(registry.get("cheap")).thenReturn(Optional.of(cheap));
        when(registry.get("pricey")).thenReturn(Optional.of(pricey));

        RoutingEngine engine = new RoutingEngine(registry, cfg, null, null,
                multiObjective, null, null, null, null, null, properties);
        RoutingRule pinnedRule = new RoutingRule("pinned", "pinned rule",
                java.util.Map.of("currency", "USD"),
                RoutingStrategy.MULTI_OBJECTIVE, List.of("pricey", "cheap"), 20);
        pinnedRule.setStrategyConfigId(9L);
        engine.addRule(pinnedRule);

        List<PaymentConnector> result = engine.resolve("USD", 1000, null);
        assertEquals("cheap", result.get(0).getId(),
                "钉死 COST 独大权重时低费率候选必须胜出（走全局 SUCCESS_RATE 链则不会）");
    }

    @Test
    @DisplayName("MULTI_OBJECTIVE: 评分服务缺失时退化为规则顺序（PRIORITY 语义）")
    void multiObjectiveWithoutServiceKeepsRuleOrder() {
        PaymentConnector cheap = connector("cheap", true, 10);
        PaymentConnector pricey = connector("pricey", true, 200);
        when(registry.get("cheap")).thenReturn(Optional.of(cheap));
        when(registry.get("pricey")).thenReturn(Optional.of(pricey));

        RoutingEngine engine = new RoutingEngine(registry, cfg, null, null,
                null, null, null, null, null, null, null);
        engine.addRule(new RoutingRule("multi", "multi rule", java.util.Map.of("currency", "USD"),
                RoutingStrategy.MULTI_OBJECTIVE, List.of("pricey", "cheap"), 20));

        List<PaymentConnector> result = engine.resolve("USD", 1000, null);
        assertEquals("pricey", result.get(0).getId());
    }

    @Test
    @DisplayName("决策审计: 支付链路（paymentId 非空）落审计并可回填 outcome；非支付链路不落")
    void auditDecisionAndOutcome() {
        RoutingDecisionRecordRepository auditRepo = mock(RoutingDecisionRecordRepository.class);
        when(auditRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(auditRepo.findById(any())).thenAnswer(inv -> {
            RoutingDecisionRecord record = new RoutingDecisionRecord();
            record.setDecisionId(inv.getArgument(0));
            return Optional.of(record);
        });
        RoutingWave16Properties syncProps = new RoutingWave16Properties();
        syncProps.getAudit().setAsyncWrite(false);
        RoutingAuditService auditService = new RoutingAuditService(auditRepo, syncProps);

        PaymentConnector chain = connector("chain", true, 5);
        PaymentConnector mock = connector("mock", true, 0);
        when(registry.get("chain")).thenReturn(Optional.of(chain));
        when(registry.get("mock")).thenReturn(Optional.of(mock));

        RoutingEngine engine = new RoutingEngine(registry, cfg, null, null,
                null, auditService, null, null, null, null, syncProps);

        // 非支付链路（paymentId=null）→ 不落审计
        engine.resolveDetailed(RoutingContext.ofPayment(null, null, java.math.BigDecimal.valueOf(1000), "NEX", null));
        verify(auditRepo, never()).save(any());

        // 支付链路 → 落审计 + outcome 回填
        RoutingDecision decision = engine.resolveDetailed(
                RoutingContext.ofPayment("pay-9", 7L, java.math.BigDecimal.valueOf(1000), "NEX", null));
        assertNotNull(decision.decisionId());
        assertTrue(decision.decisionId().startsWith("rd_"));

        engine.recordRouteOutcome(decision.decisionId(), true);
        // outcome 更新经由 audit service 的 repo.save
        verify(auditRepo, atLeast(2)).save(any());
    }

    @Test
    @DisplayName("降级链: 单候选规则命中商户降级配置时追加备选")
    void fallbackChainAppended() {
        org.nexus.gateway.orchestration.routing.fallback.FallbackRouteConfigRepository fallbackRepo =
                mock(org.nexus.gateway.orchestration.routing.fallback.FallbackRouteConfigRepository.class);
        when(fallbackRepo.findByEnabledTrueOrderByPriorityDesc()).thenReturn(List.of());
        org.nexus.gateway.orchestration.routing.fallback.FallbackRouteService fallbackService =
                new org.nexus.gateway.orchestration.routing.fallback.FallbackRouteService(fallbackRepo);

        PaymentConnector only = connector("only", true, 10);
        PaymentConnector alt = connector("alt", true, 20);
        when(registry.get("only")).thenReturn(Optional.of(only));
        when(registry.get("alt")).thenReturn(Optional.of(alt));

        // 商户级降级配置：only → alt
        org.nexus.gateway.orchestration.routing.fallback.FallbackRouteConfig config =
                new org.nexus.gateway.orchestration.routing.fallback.FallbackRouteConfig();
        config.setMerchantId(7L);
        config.setPrimaryConnector("only");
        config.setFallbackConnectorsCsv("alt");
        config.setEnabled(true);
        config.setPriority(1);
        // 通过 spy 注入配置（绕过 repo 空库限制）
        org.nexus.gateway.orchestration.routing.fallback.FallbackRouteService spyService =
                spy(fallbackService);
        doReturn(List.of("alt")).when(spyService).resolveFallbackChain(
                eq(7L), eq("only"), any(), eq("NEX"));

        RoutingEngine engine = new RoutingEngine(registry, cfg, null, null,
                null, null, null, null, spyService, null, properties);
        engine.addRule(new RoutingRule("single", "single rule", java.util.Map.of("currency", "NEX"),
                RoutingStrategy.PRIORITY, List.of("only"), 20));

        RoutingDecision decision = engine.resolveDetailed(
                RoutingContext.ofPayment("pay-1", 7L, java.math.BigDecimal.valueOf(1000), "NEX", null));
        assertEquals(2, decision.connectors().size());
        assertEquals("only", decision.connectors().get(0).getId());
        assertEquals("alt", decision.connectors().get(1).getId());
    }

    @Test
    @DisplayName("实验分流: 实验组命中时按组配置重排候选")
    void experimentReordersCandidates() {
        RoutingExperimentRepository experimentRepo = mock(RoutingExperimentRepository.class);
        RoutingExperimentService experimentService = new RoutingExperimentService(experimentRepo, properties);

        PaymentConnector a = connector("a", true, 10);
        PaymentConnector b = connector("b", true, 20);
        when(registry.get("a")).thenReturn(Optional.of(a));
        when(registry.get("b")).thenReturn(Optional.of(b));

        RoutingEngine engine = new RoutingEngine(registry, cfg, null, null,
                null, null, experimentService, null, null, null, properties);
        engine.addRule(new RoutingRule("ab", "ab rule", java.util.Map.of("currency", "NEX"),
                RoutingStrategy.PRIORITY, List.of("a", "b"), 20));

        // 100% 流量进实验组 g1（connectorIds=[b]）→ b 提到首位
        org.nexus.gateway.orchestration.routing.experiment.RoutingExperiment experiment =
                new org.nexus.gateway.orchestration.routing.experiment.RoutingExperiment();
        experiment.setExperimentId("exp_x");
        experiment.setName("x");
        experiment.setStatus("RUNNING");
        experiment.setControlGroupJson("{\"groupId\":\"control\",\"connectorIds\":[]}");
        experiment.setExperimentGroupsJson("[{\"groupId\":\"g1\",\"weight\":100,\"connectorIds\":[\"b\"]}]");
        experiment.setTargetMetric("SUCCESS_RATE");
        when(experimentRepo.findByStatus("RUNNING")).thenReturn(List.of(experiment));

        RoutingDecision decision = engine.resolveDetailed(
                RoutingContext.ofPayment("pay-1", 7L, java.math.BigDecimal.valueOf(1000), "NEX", null));
        assertEquals("exp_x", decision.experimentId());
        assertEquals("g1", decision.abTestGroup());
        assertEquals("b", decision.connectors().get(0).getId());
    }
}
