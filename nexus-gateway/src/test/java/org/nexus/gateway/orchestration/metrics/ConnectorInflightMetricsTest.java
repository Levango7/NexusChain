package org.nexus.gateway.orchestration.metrics;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.PaymentConnector;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * {@link ConnectorInflightMetrics} 的注册、导出名与 start/end 计数语义守卫。
 *
 * <h2>为什么需要这个测试</h2>
 *
 * <p>#731 落地时只有 {@code compileJava} 验证，本类补上三类断言：</p>
 * <ol>
 *   <li><b>导出的 Prometheus 名</b>与 {@code docs/ops/slo.md} §3.9 一致
 *       （{@code nexus.gateway.connector.inflight} → {@code nexus_gateway_connector_inflight}）；
 *       改名会让文档口径静默失效；</li>
 *   <li><b>start/end 计数语义</b>：各连接器独立计数、end 无条件回减、
 *       未知 id 与 null-id 连接器安全降级（不抛异常、不产生幽灵序列）；</li>
 *   <li><b>调用点守卫</b>：{@code start}/{@code end} 在 main 源码里必须仍被
 *       {@code OrchestrationService} 接线——防「注册了没人喂」这一本仓发生过
 *       两次的缺陷类（{@code nexus_3ds_auth_total}、{@code PaymentMetrics} 先例）。</li>
 * </ol>
 */
class ConnectorInflightMetricsTest {

    private static final String METRIC = ConnectorInflightMetrics.METRIC;

    private PrometheusMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    /** 两个有 id 的 mock 连接器 + 一个 null-id（模拟测试桩缺省形态）。 */
    private List<PaymentConnector> connectors(String idA, String idB, String idC) {
        PaymentConnector a = mock(PaymentConnector.class);
        PaymentConnector b = mock(PaymentConnector.class);
        PaymentConnector c = mock(PaymentConnector.class);
        lenient().when(a.getId()).thenReturn(idA);
        lenient().when(b.getId()).thenReturn(idB);
        lenient().when(c.getId()).thenReturn(idC);
        return List.of(a, b, c);
    }

    @Test
    @DisplayName("类上必须有 @Component —— 生产注册路径的前提")
    void isRegisteredAsSpringComponent() {
        assertTrue(ConnectorInflightMetrics.class.isAnnotationPresent(Component.class),
                "ConnectorInflightMetrics 必须带 @Component，否则容器里没有这个 bean，"
                        + "slo.md §3.9 登记的序列永远不会出现");
    }

    @Test
    @DisplayName("导出名为 nexus_gateway_connector_inflight，且带 connector 标签（slo.md §3.9 口径）")
    void exportsDocumentedPrometheusName() {
        ConnectorInflightMetrics metrics = new ConnectorInflightMetrics(
                connectors("psp-alpha", "psp-beta", null), registry);

        String text = registry.scrape();
        assertTrue(text.contains("nexus_gateway_connector_inflight{connector=\"psp-alpha\"}"),
                "缺少导出序列 nexus_gateway_connector_inflight{connector=\"psp-alpha\"}；"
                        + "改名会让 docs/ops/slo.md §3.9 静默失效。\n实际抓取文本：\n" + text);
        assertTrue(text.contains("nexus_gateway_connector_inflight{connector=\"psp-beta\"}"),
                "缺少 psp-beta 序列（每个连接 id 一个独立时间序列）");

        // 注册名（点号）经 Micrometer lookup 也必须可达
        assertEquals(0.0, registry.get(METRIC).tag("connector", "psp-alpha").gauge().value(), 1e-9,
                "刚注册、未 start 时在途数应为 0");
        assertEquals(metrics.current("psp-alpha"), 0);
    }

    @Test
    @DisplayName("start/end 成对：各自只动自己连接器的 gauge，不串号")
    void pairedStartEndMovesOnlyItsOwnMeter() {
        ConnectorInflightMetrics metrics = new ConnectorInflightMetrics(
                connectors("psp-alpha", "psp-beta", null), registry);

        metrics.start("psp-alpha");
        metrics.start("psp-alpha");
        metrics.start("psp-beta");

        assertEquals(2, metrics.current("psp-alpha"));
        assertEquals(1, metrics.current("psp-beta"));
        assertEquals(2.0, registry.get(METRIC).tag("connector", "psp-alpha").gauge().value(), 1e-9);
        assertEquals(1.0, registry.get(METRIC).tag("connector", "psp-beta").gauge().value(), 1e-9);

        metrics.end("psp-alpha");
        metrics.end("psp-beta");
        metrics.end("psp-alpha");

        assertEquals(0, metrics.current("psp-alpha"), "end 无条件回减，成对调用后应归 0");
        assertEquals(0, metrics.current("psp-beta"));
        assertTrue(registry.scrape().contains("nexus_gateway_connector_inflight{connector=\"psp-alpha\"} 0.0"),
                "归 0 后序列仍在（Gauge 不消失），值为 0");
    }

    @Test
    @DisplayName("未知 id 安全降级：start/end/current 都不抛异常、不产生幽灵序列")
    void unknownConnectorIdIsSafeNoOp() {
        ConnectorInflightMetrics metrics = new ConnectorInflightMetrics(
                connectors("psp-alpha", null, null), registry);

        metrics.start("no-such-connector");
        metrics.end("no-such-connector");

        assertEquals(0, metrics.current("no-such-connector"));
        assertFalse(registry.scrape().contains("no-such-connector"),
                "未知 id 不得创建新序列（动态 id 扇出会污染抓取面板）");
        assertEquals(0, metrics.current("psp-alpha"), "未知 id 的操作不得影响已注册连接器");
    }

    @Test
    @DisplayName("null-id 连接器在构造期跳过：不抛异常、不注册 gauge（mock 桩缺省形态）")
    void nullIdConnectorIsSkippedAtConstruction() {
        ConnectorInflightMetrics metrics = new ConnectorInflightMetrics(
                connectors(null, "psp-beta", null), registry);

        assertEquals(0, metrics.current(null), "null id 查询应安全返回 0");
        metrics.start(null);
        metrics.end(null);
        String text = registry.scrape();
        assertFalse(text.contains("connector=\"\""), "null id 不得注册空标签序列");
        assertTrue(text.contains("nexus_gateway_connector_inflight{connector=\"psp-beta\"}"),
                "非 null 连接器应正常注册（psp-beta）");
        long gaugeCount = text.lines().filter(l -> l.contains("nexus_gateway_connector_inflight{")).count();
        assertEquals(1, gaugeCount, "只有 1 个非 null 连接器 ⇒ 抓取文本应恰好 1 条 inflight 序列");
    }

    @Test
    @DisplayName("并发 start 各自计数：屏障后 N 线程在途数恰为 N，全部 end 后归 0")
    void concurrentStartsAreCountedIndependently() throws Exception {
        ConnectorInflightMetrics metrics = new ConnectorInflightMetrics(
                connectors("psp-alpha", null, null), registry);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    metrics.start("psp-alpha");
                    done.countDown();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "线程未全部就绪");
        go.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "start 未在时限内完成");
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        Throwable t = failure.get();
        if (t != null) {
            throw new IllegalStateException("并发 start 抛出异常", t);
        }
        assertEquals(threads, metrics.current("psp-alpha"),
                "8 个并发 start 后在途数应为 8（AtomicInteger 计数不得丢增）");

        for (int i = 0; i < threads; i++) {
            metrics.end("psp-alpha");
        }
        assertEquals(0, metrics.current("psp-alpha"), "成对 end 后应归 0");
    }

    /**
     * 静态调用点守卫（口径沿 {@code PaymentMetricsTest}）：
     * {@code OrchestrationService} 源码里必须仍存在
     * {@code inflightMetrics.start(} / {@code inflightMetrics.end(} 调用。
     * 粗粒度、会漏（反射抓不到），但拦得住「整类 meter 没人喂」的退化。
     */
    @Test
    @DisplayName("start/end 在 OrchestrationService 里仍有调用点（防「注册了没人喂」回归）")
    void startEndHaveACallSiteInOrchestrationService() throws IOException {
        Path mainSrc = Paths.get("src/main/java");
        assertTrue(Files.isDirectory(mainSrc),
                "预期在 nexus-gateway 模块目录下运行（找不到 " + mainSrc.toAbsolutePath() + "）");

        Path service = null;
        try (Stream<Path> files = Files.walk(mainSrc)) {
            service = files.filter(p -> p.getFileName().toString()
                            .equals("OrchestrationService.java"))
                    .findFirst().orElse(null);
        }
        assertTrue(service != null, "找不到 OrchestrationService.java（接线点文件被移动？）");
        String source = Files.readString(service);

        assertTrue(source.contains("inflightMetrics.start("),
                "OrchestrationService 不再调用 inflightMetrics.start() —— "
                        + "nexus_gateway_connector_inflight 会注册但恒为 0，这正是"
                        + " PaymentMetrics/nexus_3ds_auth_total 先例的缺陷形态。"
                        + "接线点见 docs/ops/slo.md §3.9。");
        assertTrue(source.contains("inflightMetrics.end("),
                "OrchestrationService 不再调用 inflightMetrics.end() —— "
                        + "缺 end 只增不减，计数只涨不跌（泄漏形态比恒 0 更糟）");

        // 反向自检：守卫本身不能是空转
        assertFalse(source.contains("thisMethodDoesNotExistAnywhere("),
                "守卫自检失败：语料里居然有这个不存在的调用");
    }
}
