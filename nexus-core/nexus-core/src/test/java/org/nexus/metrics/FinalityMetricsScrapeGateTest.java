package org.nexus.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.command.LocalRpcInterceptor;
import org.nexus.command.RpcInterceptor;
import org.nexus.command.WebSecurityConfig;
import org.nexus.consensus.finality.FinalityGadget;
import org.nexus.consensus.finality.FinalityRejectionCounter.Phase;
import org.nexus.consensus.finality.FinalityRejectionCounter.Reason;
import org.nexus.consensus.finality.Vote;
import org.nexus.consensus.finality.net.FinalityVoteBroadcaster;
import org.nexus.consensus.finality.net.FinalityVoteCodec;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.StakingServiceImpl;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.core.Block;
import org.nexus.crypto.ed25519.Ed25519;
import org.nexus.crypto.ed25519.Ed25519KeyPair;
import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PLAN-068：最终性拒绝指标的<b>抓取端可见性</b>门禁。
 *
 * <h2>缺口（为什么要这个用例）</h2>
 * <p>PLAN-066/067 把拒绝原因做成了 {@code nexus_finality_vote_rejected_total}，
 * 两版交付文档也都登记了「未做 {@code /actuator/prometheus} 抓取端补验」。
 * 此前全仓 {@code src/test} 里搜 {@code actuator/prometheus} 只有 2 处<b>注释</b>，
 * <b>没有任何断言</b>回答过这两个问题：</p>
 * <ol>
 *   <li>抓取请求不带 token 时，{@code localonly=true} 的鉴权链会不会把它挡成 401？
 *       （{@code LocalRpcInterceptor} 挂在 {@code /**}，白名单里没有 {@code /actuator/*}）</li>
 *   <li>指标序列在<b>真实抓取文本</b>里长什么样？标签名、标签值、{@code _total} 后缀、
 *       计数格式是否与告警规则 {@code expr} 里写的逐字一致？</li>
 * </ol>
 *
 * <h2>本用例钉住的事实（2026-11-02 运行时实证的固化）</h2>
 * <ul>
 *   <li>Spring Boot 4.0.8 的 actuator 端点由<b>独立</b>
 *       {@code WebMvcEndpointHandlerMapping} 提供，构造时不调用
 *       {@code setInterceptors} ⇒ {@code LocalRpcInterceptor} <b>不作用于</b>
 *       {@code /actuator/**}；实测无 token 抓取为 <b>200</b>；</li>
 *   <li>同一进程里普通业务端点（{@code /p068-probe-guarded}）仍是 <b>401</b>
 *       ——所以"200"是 actuator 路由的固有属性，不是鉴权被关掉了；</li>
 *   <li>三个新 reason 的序列在抓取文本中逐个可见、计数正确。</li>
 * </ul>
 *
 * <h2>为什么不用 {@code @SpringBootTest(classes = Start.class)}</h2>
 * <p>完整启动 {@code Start} 需要 PG、LevelDB、genesis、JWT 密钥与 P2P 监听端口，
 * 属于集成环境而非单测能稳定复现的形态。本用例只装配<b>与本缺口相关</b>的
 * 三块（actuator 自动配置 + {@link WebSecurityConfig} 拦截器 + 指标适配器），
 * 用真实内嵌 Tomcat 与真实 HTTP 抓取文本断言。配置值取自
 * <b>生产 {@code application.properties}</b>（见 {@link #productionConfigStillExposesPrometheus()}），
 * 不用测试专用的替代值，避免"测试配置绿、生产配置红"。</p>
 *
 * @since 2.79.0
 */
@SpringBootTest(
        classes = FinalityMetricsScrapeGateTest.TestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 只替换数据源（本用例不需要库）；指标暴露值刻意不覆盖，
                // 直接沿用生产 application.properties。
                "spring.datasource.url=jdbc:h2:mem:p068_scrape_gate;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver"
        })
class FinalityMetricsScrapeGateTest {

    /** 与生产一致的 local-only 取值：本缺口只在 localonly=true 下才有意义。 */
    private static final String SCRAPE_PATH = "/actuator/prometheus";

    /**
     * 用 JDK 自带 {@link HttpClient} 而不是 {@code TestRestTemplate}：
     * 后者在 Boot 4 已迁到 {@code org.springframework.boot.resttestclient}
     * 且不是 starter-test 的传递依赖。直接发原始 HTTP 也更能保证"不带 token 头"
     * 是字面事实（无隐式加头、无自动重试/重定向）。
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @LocalServerPort
    private int port;

    /** Spring 管理的指标适配器：与 {@code FinalityGadget} 生产注入的是同一个 bean。 */
    @Autowired
    private FinalityRejectionMetrics metrics;

    @Test
    @DisplayName("不带 token 抓取 /actuator/prometheus 返回 200，且三个新 reason 在文本中逐个可见")
    void scrapeWithoutTokenExposesTheThreeNewRejectionReasons() {
        // 用真实的拒绝判定路径（不是直接调 counter），把计数写进同一个 Spring bean。
        FinalityGadget gadget = newRealGadget();
        gadget.setRejectionCounter(metrics);

        // ① checkpoint_hash_invalid@admission：短哈希在注册表/验签之前就被挡下。
        gadget.submitVote(new Vote(1, new byte[]{1, 2, 3}, "p068-v1", new byte[0], new byte[0]));

        // ② checkpoint_limit_exceeded@admission：同一验证人同 epoch 的第 5 个新键。
        for (int i = 0; i < 5; i++) {
            gadget.submitVote(signedVote("p068-v2", 2, filledHash(0x40 + i)));
        }

        // ③ payload_too_large@admission：走 broadcaster 的入站尺寸闸（真实编解码路径）。
        FinalityVoteBroadcaster broadcaster = new FinalityVoteBroadcaster(gadget, event -> { });
        broadcaster.setRejectionCounter(metrics);
        byte[] oversized = FinalityVoteCodec.encode(new Vote(
                1, new byte[FinalityVoteCodec.MAX_ENCODED_VOTE_BYTES], "p068-v3",
                new byte[64], new byte[32]));
        assertTrue(oversized.length > FinalityVoteCodec.MAX_ENCODED_VOTE_BYTES,
                "用例前提：该载荷必须确实超过入站上限");
        broadcaster.onVoteReceived(oversized);

        // 真实 HTTP 抓取（不带 token 头），断言的是**抓取文本**而不是 registry 内部状态。
        HttpResponse<String> response = get(SCRAPE_PATH);
        assertEquals(200, response.statusCode(),
                "localonly=true 下 actuator 抓取必须可达；401 意味着 20 条告警永远不会触发");
        String text = response.body();
        assertNotNull(text, "抓取响应体不得为空");

        assertSeries(text, "admission", "checkpoint_hash_invalid", 1);
        assertSeries(text, "admission", "checkpoint_limit_exceeded", 1);
        assertSeries(text, "admission", "payload_too_large", 1);

        // 对照：同一进程里普通业务端点仍被 local-only 鉴权挡住 ⇒ 上面的 200 不是因为鉴权被关掉。
        HttpResponse<String> guarded = get("/p068-probe-guarded");
        assertEquals(401, guarded.statusCode(),
                "普通端点必须仍返回 401，否则本用例证明不了 actuator 的路由特性");
    }

    @Test
    @DisplayName("生产配置仍暴露 prometheus 端点（抓取可达性的配置侧前提）")
    void productionConfigStillExposesPrometheus() throws IOException {
        Properties props = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            assertNotNull(in, "找不到生产 application.properties");
            props.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        String exposed = props.getProperty("management.endpoints.web.exposure.include", "");
        assertTrue(Arrays.stream(exposed.split(",")).map(String::trim).anyMatch("prometheus"::equals),
                "生产配置必须暴露 prometheus 端点，否则 ServiceMonitor 会抓到 404：" + exposed);

        assertTrue(props.getProperty("server.port", "").contains("19585"),
                "actuator 必须与 RPC 同端口（ServiceMonitor 的 port: rpc = 19585）");
        assertTrue(props.getProperty("management.server.port") == null,
                "不得单独给 actuator 配端口，否则 ServiceMonitor/网络策略需同步改");
    }

    // ------------------------------------------------------------------ helpers

    /** 发一个<b>不带 token 头</b>的 GET（只带 Accept，模拟 Prometheus 抓取客户端）。 */
    private HttpResponse<String> get(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/plain;version=0.0.4")
                .GET()
                .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new AssertionError("抓取请求失败: " + path, e);
        }
    }

    /** 抓取文本里必须存在该序列且计数等于期望值（Prometheus 文本格式逐字匹配）。 */
    private static void assertSeries(String text, String phase, String reason, double expected) {
        String series = "nexus_finality_vote_rejected_total{phase=\"" + phase
                + "\",reason=\"" + reason + "\"}";
        String line = text.lines()
                .filter(l -> l.startsWith(series + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "抓取文本中缺少序列 " + series + "\n实际含 vote_rejected 的行：\n"
                                + text.lines().filter(l -> l.contains("vote_rejected"))
                                .reduce("", (a, b) -> a + b + "\n")));
        String value = line.substring(series.length()).trim();
        assertEquals(expected, Double.parseDouble(value),
                "序列 " + series + " 的计数必须是 " + expected + "（抓取文本：" + line + "）");
        assertTrue(text.contains("# TYPE nexus_finality_vote_rejected_total counter"),
                "counter 类型声明必须存在，否则 PromQL 的 increase() 语义会变");
    }

    private static byte[] filledHash(int seed) {
        byte[] h = new byte[Block.HASH_SIZE];
        Arrays.fill(h, (byte) seed);
        return h;
    }

    /** 真实 gadget：注册表 + 质押账本 + 可签名的验证人密钥（与 PLAN-067 用例同构）。 */
    private FinalityGadget newRealGadget() {
        ValidatorRegistry registry = new ValidatorRegistry(new BigDecimal("100"), 100);
        StakingServiceImpl staking = new StakingServiceImpl();
        inject(staking, "validatorRegistry", registry);

        for (String address : new String[]{"p068-v2", "p068-v3"}) {
            Ed25519KeyPair keyPair = Ed25519.generateKeyPair();
            keys.put(address, keyPair);
            registry.register(address, Hex.encodeHexString(keyPair.getPublicKey().getEncoded()),
                    new BigDecimal(300), 0.1);
            registry.getValidator(address).setStatus(ValidatorStatus.ACTIVE);
            staking.stake(address, new BigDecimal(300));
        }
        return new FinalityGadget(registry, staking);
    }

    private final Map<String, Ed25519KeyPair> keys = new HashMap<>();

    private Vote signedVote(String validator, long epoch, byte[] checkpoint) {
        Ed25519KeyPair keyPair = keys.get(validator);
        byte[] publicKey = keyPair.getPublicKey().getEncoded();
        Vote unsigned = new Vote(epoch, checkpoint, validator, new byte[0], publicKey);
        try {
            byte[] signature = keyPair.getPrivateKey().sign(unsigned.signingPayload());
            return new Vote(epoch, checkpoint, validator, signature, publicKey);
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 signing failed for test vote", e);
        }
    }

    private static void inject(StakingService target, String fieldName, Object value) {
        try {
            Field field = StakingServiceImpl.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ test app

    /**
     * 最小装配：只含本缺口涉及的三块。
     *
     * <p>不组件扫描整个 {@code org.nexus}——那会把 PG/LevelDB/P2P 依赖拉进上下文。
     * 数据源自动配置显式排除：本用例只验抓取与鉴权，不验持久化。</p>
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class
    })
    @Import({WebSecurityConfig.class, LocalRpcInterceptor.class, RpcInterceptor.class,
            FinalityRejectionMetrics.class, ProbeController.class})
    static class TestApp {
    }

    /** 普通业务端点的替身：用于对照"拦截器确实在工作"。 */
    @RestController
    static class ProbeController {

        @GetMapping("/p068-probe-guarded")
        String guarded() {
            return "should-not-be-reachable-without-token";
        }
    }
}
