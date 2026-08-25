package org.nexus.gateway.orchestration.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.orchestration.connector.ConnectorRegistry;
import org.nexus.gateway.orchestration.model.RoutingRuleEntity;
import org.nexus.gateway.orchestration.repository.RoutingRuleEntityRepository;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link RoutingEngine} 持久化行为单元测试（纯 Mockito，无 Spring 上下文）：
 * <ul>
 *   <li>启动 seed：DB 为空时将内存默认规则写入 DB；</li>
 *   <li>重启恢复：DB 有数据时以 DB 为准重建内存；</li>
 *   <li>write-through：addRule/updateRule/removeRule 同步 DB 且异常不外溢；</li>
 *   <li>旧构造器兼容：repo=null 时纯内存行为不变。</li>
 * </ul>
 */
class RoutingEnginePersistenceTest {

    private final ConnectorRegistry registry = mock(ConnectorRegistry.class);
    private final GatewayConfig cfg = new GatewayConfig(); // dual-chain 默认关闭
    private final RoutingRuleEntityRepository repo = mock(RoutingRuleEntityRepository.class);

    private static RoutingRuleEntity entity(String id, Map<String, String> conditions,
                                            List<String> connectors, int priority) {
        RoutingRuleEntity e = new RoutingRuleEntity();
        e.setId(id);
        e.setName("rule-" + id);
        e.setConditionsJson(RoutingRuleEntity.toConditionsJson(conditions));
        e.setStrategy(RoutingStrategy.PRIORITY.name());
        e.setConnectorsCsv(String.join(",", connectors));
        e.setPriority(priority);
        return e;
    }

    @Test
    @DisplayName("seedToDbWhenEmpty: DB为空 -> 构造引擎时 saveAll 幂等种子且包含 default-nex")
    void seedToDbWhenEmpty() {
        when(repo.count()).thenReturn(0L);

        new RoutingEngine(registry, cfg, null, repo);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RoutingRuleEntity>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(repo, atLeastOnce()).saveAll(captor.capture());

        List<RoutingRuleEntity> seeded = captor.getValue();
        assertTrue(seeded.stream().anyMatch(e -> "default-nex".equals(e.getId())),
                "seeded entities must contain default-nex");
        assertTrue(seeded.stream().anyMatch(e -> "default-fallback".equals(e.getId())),
                "seeded entities must contain default-fallback");
        assertEquals(2, seeded.size());
    }

    @Test
    @DisplayName("restoreFromDbWhenHasData: DB有数据 -> 清空内存并以DB为准恢复运营配置")
    void restoreFromDbWhenHasData() {
        when(repo.count()).thenReturn(2L);
        when(repo.findAll()).thenReturn(List.of(
                entity("default-nex", Map.of("currency", "NEX"), List.of("chain", "mock"), 10),
                entity("custom-vip", Map.of("currency", "USD"), List.of("stripe", "mock"), 100)));

        RoutingEngine engine = new RoutingEngine(registry, cfg, null, repo);

        List<RoutingRule> rules = engine.getRules();
        assertEquals(2, rules.size(), "in-memory rules must be replaced by DB contents");
        assertTrue(rules.stream().anyMatch(r -> "custom-vip".equals(r.getId())),
                "restored rules must contain the custom rule");
        rules.stream()
                .filter(r -> "custom-vip".equals(r.getId()))
                .findFirst()
                .ifPresentOrElse(rule -> {
                    assertEquals(100, rule.getPriority());
                    assertEquals(List.of("stripe", "mock"), rule.getConnectors());
                    assertEquals(Map.of("currency", "USD"), rule.getConditions());
                    assertEquals(RoutingStrategy.PRIORITY, rule.getStrategy());
                }, () -> fail("custom-vip rule missing"));
    }

    @Test
    @DisplayName("updateRulePersists: updateRule 后 write-through 调用 save")
    void updateRulePersists() {
        when(repo.count()).thenReturn(0L);
        RoutingEngine engine = new RoutingEngine(registry, cfg, null, repo);

        RoutingRule updated = new RoutingRule("r1", "updated rule",
                Map.of("currency", "EUR"), RoutingStrategy.COST, List.of("stripe"), 20);
        RoutingRule result = engine.updateRule("r1", updated);

        assertSame(updated, result);
        assertEquals("r1", result.getId());

        ArgumentCaptor<RoutingRuleEntity> captor = ArgumentCaptor.forClass(RoutingRuleEntity.class);
        verify(repo).save(captor.capture());
        RoutingRuleEntity saved = captor.getValue();
        assertEquals("r1", saved.getId());
        assertEquals(RoutingStrategy.COST.name(), saved.getStrategy());
        assertEquals("stripe", saved.getConnectorsCsv());
        assertEquals(20, saved.getPriority());
        assertEquals("{\"currency\":\"EUR\"}", saved.getConditionsJson());
    }

    @Test
    @DisplayName("removeRuleDeletes: removeRule 后调用 deleteById")
    void removeRuleDeletes() {
        when(repo.count()).thenReturn(0L);
        RoutingEngine engine = new RoutingEngine(registry, cfg, null, repo);

        engine.removeRule("default-nex");

        assertEquals(1, engine.getRules().size());
        verify(repo).deleteById("default-nex");
    }

    @Test
    @DisplayName("legacyConstructorNoDb: 旧构造器(repo=null) addRule/removeRule 正常无 NPE")
    void legacyConstructorNoDb() {
        RoutingEngine twoArgEngine = new RoutingEngine(registry, cfg);
        RoutingEngine threeArgEngine = new RoutingEngine(registry, cfg, null);

        assertDoesNotThrow(() -> {
            RoutingRule r = new RoutingRule("m1", "memory rule",
                    Map.of("currency", "NEX"), RoutingStrategy.PRIORITY, List.of("chain"), 30);
            twoArgEngine.addRule(r);
            threeArgEngine.addRule(r);
        });
        verifyNoInteractions(repo); // 两个旧构造器根本不持有 repo

        assertEquals(3, twoArgEngine.getRules().size()); // 2 default + m1
        threeArgEngine.removeRule("m1");
        twoArgEngine.removeRule("m1");
        assertEquals(2, twoArgEngine.getRules().size());
        assertEquals(twoArgEngine.getRules().size(), threeArgEngine.getRules().size());
    }

    @Test
    @DisplayName("dbFailureDoesNotBlockMemory: write-through DB 异常不阻断内存操作")
    void dbFailureDoesNotBlockMemory() {
        when(repo.count()).thenReturn(0L);
        doThrow(new IllegalStateException("db down")).when(repo).save(any(RoutingRuleEntity.class));
        doThrow(new IllegalStateException("db down")).when(repo).deleteById(any(String.class));
        RoutingEngine engine = new RoutingEngine(registry, cfg, null, repo);

        RoutingRule r = new RoutingRule("r9", "resilient rule", Map.of(),
                RoutingStrategy.PRIORITY, List.of("mock"), 5);
        assertDoesNotThrow(() -> engine.addRule(r));
        assertEquals(3, engine.getRules().size()); // 内存仍成功写入

        assertDoesNotThrow(() -> engine.removeRule("r9"));
        assertEquals(2, engine.getRules().size()); // 内存仍成功删除

        // 启动期 DB 同步失败同样不阻断初始化（保持内存默认规则）
        when(repo.count()).thenThrow(new IllegalStateException("db down"));
        assertDoesNotThrow(() -> {
            RoutingEngine degraded = new RoutingEngine(registry, cfg, null, repo);
            assertEquals(2, degraded.getRules().size());
        });
    }
}