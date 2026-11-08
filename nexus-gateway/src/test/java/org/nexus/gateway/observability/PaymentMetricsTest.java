package org.nexus.gateway.observability;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PaymentMetrics} 的指标名、计数语义与「有人喂」守卫。
 *
 * <h2>为什么需要这个测试</h2>
 *
 * <p>2026-11-07 审计发现：{@code PaymentMetrics} 注册的 7 个 meter
 * <b>全仓零调用点</b>。因为注册发生在 {@code @Component} 的构造器里，
 * {@code /actuator/prometheus} <b>能看到这些序列、值恒为 0</b>——
 * 单测只 {@code new} 一个对象、断言「名字注册上了」是<b>测不出</b>这个缺陷的：
 * 缺陷在「没有调用点」，不在「没注册」。</p>
 *
 * <p>所以本测试断三件不同的事：</p>
 * <ol>
 *   <li><b>导出的 Prometheus 名</b>与文档/面板引用一致（改名字会让
 *       {@code deploy/grafana/dashboards/nexus-gateway.json} 静默空白）；</li>
 *   <li><b>每个自增方法的语义</b>（各自只动自己那个 meter，不串号）；</li>
 *   <li><b>每个自增方法都有调用点</b>——这是对「注册了没人喂」这一缺陷类的
 *       静态守卫。粗粒度、会漏（反射调用抓不到），但足以拦住本仓已经发生过
 *       两次的那种退化。</li>
 * </ol>
 *
 * <p>同类先例：{@code nexus_3ds_auth_total} 曾在 {@code SecurityMetricsRecorder}
 * 里「注册后从来没人喂过，此前恒为 0」，后由 {@code ThreeDsAuthCompletedObserver}
 * 补上调用点修复（见该文件 javadoc）。</p>
 */
class PaymentMetricsTest {

    /**
     * 注册名 → 导出名。
     *
     * <p>Micrometer 的 {@code registry.get(...)} 用<b>注册名</b>（点号），
     * Prometheus 抓取时才转成下划线 + {@code _total}。两个名字都要断，
     * 所以这里成对维护。</p>
     */
    private static final List<String[]> METERS = List.of(
            // ⚠ 这一对**不**是简单加 `_total`：OpenMetrics 把 `_created` 保留给
            // 「创建时间戳」序列，Prometheus 客户端在导出时会剥掉这个名字后缀，
            // 于是 nexus.orders.created → nexus_orders_total（实测抓取文本见本类断言消息）。
            // 2026-11-07 修正：面板此前查的是 nexus_orders_created_total，该序列**不存在**。
            new String[]{"nexus.orders.created", "nexus_orders_total"},
            new String[]{"nexus.payments.confirmed", "nexus_payments_confirmed_total"},
            new String[]{"nexus.payments.failed", "nexus_payments_failed_total"},
            new String[]{"nexus.refunds.issued", "nexus_refunds_issued_total"},
            new String[]{"nexus.webhooks.delivered", "nexus_webhooks_delivered_total"},
            new String[]{"nexus.webhooks.failed", "nexus_webhooks_failed_total"});

    /** 注册在 PaymentMetrics 里的自增方法（不含 Timer 的 getter）。 */
    private static final List<String> INCREMENT_METHODS = List.of(
            "orderCreated", "paymentConfirmed", "paymentFailed",
            "refundIssued", "webhookDelivered", "webhookFailed");

    private PrometheusMeterRegistry registry;
    private PaymentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new PaymentMetrics(registry);
    }

    @Test
    @DisplayName("类上必须有 @Component —— 生产注册路径的前提")
    void isRegisteredAsSpringComponent() {
        assertTrue(PaymentMetrics.class.isAnnotationPresent(Component.class),
                "PaymentMetrics 必须带 @Component，否则容器里没有这个 bean，"
                        + "所有业务面板永远拿不到序列");
    }

    @Test
    @DisplayName("导出的 Prometheus 名与面板/文档引用一致（含 Timer 的 _seconds 后缀）")
    void exportsDocumentedPrometheusNames() {
        String text = registry.scrape();
        for (String[] pair : METERS) {
            assertTrue(text.contains(pair[1]),
                    "缺少导出序列 " + pair[1] + "；改名会让 Grafana 面板与 SLO 文档静默失效。\n实际抓取文本：\n" + text);
        }
        assertTrue(text.contains("nexus_payment_latency_seconds_count"),
                "Timer 未导出 _seconds_count");
        assertTrue(text.contains("nexus_payment_latency_seconds_sum"),
                "Timer 未导出 _seconds_sum");
        assertTrue(text.contains("nexus_payment_latency_seconds_max"),
                "Timer 未导出 _seconds_max（面板的「最慢一次」格依赖它）");
    }

    @Test
    @DisplayName("每个自增方法只动自己那个 meter，不串号")
    void eachIncrementMovesItsOwnMeter() {
        metrics.orderCreated();
        metrics.paymentConfirmed();
        metrics.paymentFailed();
        metrics.refundIssued();
        metrics.webhookDelivered();
        metrics.webhookFailed();

        for (String[] pair : METERS) {
            assertEquals(1.0, registry.get(pair[0]).counter().count(), 1e-9,
                    pair[0] + " 的计数应为 1");
        }
    }

    @Test
    @DisplayName("端到端耗时：创建时间未知或未来时跳过，绝不记 0")
    void latencySkipsUnknownOrFutureCreationTime() {
        metrics.paymentLatencySince(null);
        assertEquals(0L, registry.get("nexus.payment.latency").timer().count(),
                "createdAt 为 null 时不得记录样本（记 0 会让 p50/p95 假装存在）");

        metrics.paymentLatencySince(LocalDateTime.now().plusMinutes(5));
        assertEquals(0L, registry.get("nexus.payment.latency").timer().count(),
                "创建时间在未来（时钟回拨）时不得记录样本");

        metrics.paymentLatencySince(LocalDateTime.now().minusSeconds(2));
        assertEquals(1L, registry.get("nexus.payment.latency").timer().count(),
                "正常的创建时间应记录 1 个样本");
    }

    /**
     * 静态调用点守卫。
     *
     * <p><b>口径</b>：在 gateway 模块的 {@code src/main/java} 下（排除本类自身）
     * 至少出现一次 {@code <方法名>(}。这是粗粒度检查——注释里提到方法名也算通过，
     * 反射调用抓不到。它拦的是「整类 meter 一个调用点都没有」这种退化，
     * 不是精确的调用图分析。</p>
     */
    @Test
    @DisplayName("每个自增方法在 main 源码里都有调用点（防「注册了没人喂」回归）")
    void everyIncrementMethodHasACallSite() throws IOException {
        Path mainSrc = Paths.get("src/main/java");
        assertTrue(Files.isDirectory(mainSrc),
                "预期在 nexus-gateway 模块目录下运行（找不到 " + mainSrc.toAbsolutePath() + "）");

        StringBuilder corpus = new StringBuilder();
        try (Stream<Path> files = Files.walk(mainSrc)) {
            for (Path p : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                if (p.getFileName().toString().equals("PaymentMetrics.java")) {
                    continue;
                }
                corpus.append(Files.readString(p)).append('\n');
            }
        }
        String source = corpus.toString();

        for (String method : INCREMENT_METHODS) {
            assertTrue(source.contains(method + "("),
                    "PaymentMetrics." + method + "() 在 main 源码里没有任何调用点 —— "
                            + "meter 会被注册、序列会出现在 /actuator/prometheus，但值恒为 0。"
                            + "这正是 2026-11-07 审计发现的缺陷形态；接线点见 docs/ops/slo.md。");
        }
        // 反向自检：守卫本身不能是空转（随便编一个方法名必须判红）
        assertFalse(source.contains("thisMethodDoesNotExistAnywhere("),
                "守卫自检失败：语料里居然有这个不存在的调用");
    }
}
