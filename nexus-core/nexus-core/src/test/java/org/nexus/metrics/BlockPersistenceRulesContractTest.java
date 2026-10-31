package org.nexus.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.core.PendingBlocksManager;
import org.nexus.db.BlockWriteStats;
import org.nexus.db.StateDB;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 区块持久化指标 ↔ 告警规则名契约门禁（PLAN-062）。
 *
 * <p><b>缺口</b>：{@code BlockPersistenceMetricsTest} 锁定的是指标<b>行为</b>
 * （单调性 / NaN 语义 / readable 翻转），{@code WiringAuditTest} 锁的是
 * <b>注册路径</b>（MeterBinder 必须带 @Component）。两者之间有一条没人守的缝：</p>
 *
 * <ul>
 *   <li><b>规则 → 代码</b>：{@code deploy/k8s/50-prometheus-rules.yaml} 的
 *       {@code nexuschain.block-persistence} 组引用了 {@code nexus_block_*} 名字。
 *       若有人在 Prometheus 端把名字写错/改旧，或有人在代码里改了名字，
 *       这条告警就<b>永远不会触发</b>——而现有测试全绿（它们根本不读规则文件）；
 *       PLAN-058 的教训正是"指标从未注册 ⇒ 三条告警永不会触发"，此处是同型第 6 例。</li>
 *   <li><b>代码 → 规则</b>：新加一个指标却没有任何告警消费它 = 白暴露，
 *       目前也没有任何测试会问"这个指标有人在看吗"。</li>
 * </ul>
 *
 * <p>本测试把两个方向都钉死：绑定一次真 {@link SimpleMeterRegistry} 拿到<b>实际注册</b>
 * 的指标名，再解析 Prometheus 规则里 block-persistence 组的 {@code expr}，
 * 对比两侧名字。改名/漏接任一方向，CI 直接红。</p>
 *
 * <p>与 {@code BlockPersistenceMetricsTest} 的分工：那个测"值对不对"，
 * 本测试测"名字接没接上"；finality 侧的同型门禁见
 * {@link FinalityRulesContractTest}，规则文件解析两者共用
 * {@link PrometheusRulesFixture}。</p>
 */
class BlockPersistenceRulesContractTest {

    private static final String RULE_GROUP = "nexuschain.block-persistence";

    /**
     * 已注册但<b>没有</b>告警消费的指标白名单（诊断用 Gauge）。
     *
     * <p>保留原因是如实记录的：这 5 个服务于人工排障（配合
     * {@code GET /consensus/block-writer} / {@code block-outbox} 端点与
     * BlockPersistenceBacklog / BlockWriteFailuresDetected 的 description），
     * 不是缺陷。白名单的作用是：将来新加指标必须被<b>有意识地</b>分类到这里，
     * 而不是静默变成又一个没人看的指标。</p>
     */
    private static final Set<String> ALERTLESS_BY_DESIGN = Set.of(
            // 最近失败尝试的高度/年龄：BlockWriteFailuresDetected 的 description
            // 指向端点与日志；这两个 Gauge 不单独告警（高度本身不等于停摆）。
            "nexus_block_writer_last_failure_height",
            "nexus_block_writer_last_failure_age_seconds",
            // outbox 装配开关：BlockOutboxUnreadable 已用 readable==0 覆盖"读不到"，
            // enabled 只回答"有没有装"，单独告警无意义。
            "nexus_block_outbox_enabled",
            // 滞留行年龄 / 最大重试次数：BlockPersistenceBacklog 已用
            // memory_pending>0 or database_pending>0 表达"有积压"，
            // 这两个 Gauge 是积压的<b>严重程度</b>而非<b>存在性</b>，供干线人读。
            "nexus_block_outbox_oldest_pending_age_seconds",
            "nexus_block_outbox_max_attempts"
    );

    private Set<String> registeredNames;

    @BeforeEach
    void bindRealRegistry() {
        StateDB stateDB = mock(StateDB.class);
        BlockWriteStats stats = new BlockWriteStats();
        when(stateDB.blockWriteStats()).thenReturn(stats);
        PendingBlocksManager pendingBlocksManager = mock(PendingBlocksManager.class);

        BlockPersistenceMetrics metrics =
                new BlockPersistenceMetrics(stateDB, pendingBlocksManager);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        metrics.bindTo(registry);

        registeredNames = new TreeSet<>();
        registry.getMeters().forEach(m -> registeredNames.add(m.getId().getName()));

        // 防假绿：绑定结果必须正是那 10 个（少一个 = 门禁本身失灵）
        assertEquals(10, registeredNames.size(),
                "BlockPersistenceMetrics.bindTo 应注册 10 个指标，实际：" + registeredNames);
    }

    @Test
    @DisplayName("告警规则引用的每个 nexus_block_* 名字都必须在代码里注册")
    void everyRuleReferencedNameIsRegistered() throws IOException {
        Set<String> referenced = PrometheusRulesFixture.normalizedMeterNames(RULE_GROUP);

        Set<String> unknown = new LinkedHashSet<>();
        for (String name : referenced) {
            if (!registeredNames.contains(name)) {
                unknown.add(name);
            }
        }

        assertTrue(unknown.isEmpty(),
                "Prometheus 规则引用了代码里未注册的指标名（该告警永远不触发）: " + unknown
                        + "\n  规则引用: " + referenced
                        + "\n  已注册:   " + registeredNames);
    }

    @Test
    @DisplayName("每个已注册指标要么被告警消费，要么在白名单里登记用途")
    void everyRegisteredNameIsAlertedOrDocumented() throws IOException {
        // 归一化到 registry 侧的名字：counter 在 Prometheus 侧带 _total，registry 里不带
        Set<String> referenced = PrometheusRulesFixture.normalizedMeterNames(RULE_GROUP);
        assertEquals(5, referenced.size(),
                "block-persistence 组的 expr 应引用 5 个指标（"
                        + "write_failures/state_update_timeouts/memory_pending/"
                        + "database_pending/readable），实际: " + referenced);

        Set<String> uncovered = new TreeSet<>(registeredNames);
        uncovered.removeAll(referenced);
        uncovered.removeAll(ALERTLESS_BY_DESIGN);

        assertTrue(uncovered.isEmpty(),
                "存在既无告警消费、也未在 ALERTLESS_BY_DESIGN 登记用途的指标"
                        + "（新指标必须有意识地分类）：" + uncovered
                        + "\n  已注册: " + registeredNames
                        + "\n  告警引用: " + referenced);
    }
}
