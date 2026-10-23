package org.nexus.gateway.security;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.audit.SecurityMetricsRecorder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 防重放拦截计数（{@code nexus_replay_interceptions_total}）的接线测试。
 *
 * <p>这个 counter 此前在全仓<b>零调用方</b>——恒 0 的告警指标比没有指标更糟，因为它让人以为
 * "重放有在盯"。本文件钉住三条：① 两条真·防重放判据（时间戳出窗、nonce 重复）各计一次；
 * ② 签名伪造类拒绝（40101/40107）不混进来，否则一个数回答不了任何问题；
 * ③ 记录器缺席时不许 NPE——拦截本身照旧发生（可观测性是旁路，不能变成新的失败模式）。</p>
 *
 * <p>装配走 Spring 那条构造器：用真容器取 {@code ObjectProvider}，而不是手搓一个假的 provider——
 * 这样"容器里有 bean"与"容器里确实没有 bean"两种形态都是容器自己给出的。</p>
 */
class RequestSignatureReplayMetricsTest {

    private static final String SECRET = "test-secret";
    private static final String METRIC = "nexus_replay_interceptions_total";

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    // ==================== 会计数的两条判据 ====================

    @Test
    @DisplayName("时间戳出防重放窗口 → 计一次")
    void timestampOutsideWindowCountsAsReplayInterception() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestSignatureInterceptor interceptor = interceptorWith(new SecurityMetricsRecorder(registry));

        MockHttpServletRequest req = signedRequest();
        req.addHeader("X-NexusChain-Timestamp", String.valueOf(System.currentTimeMillis() - 400_000L));
        req.addHeader("X-NexusChain-Nonce", nonce());

        assertFalse(interceptor.preHandle(req, new MockHttpServletResponse(), null));
        assertEquals(1.0, count(registry), "40103（出窗）应计一次拦截");
    }

    @Test
    @DisplayName("同一 nonce 第二次出现 → 计一次；第一次出现不计")
    void repeatedNonceCountsOnlyTheSecondTime() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestSignatureInterceptor interceptor = interceptorWith(new SecurityMetricsRecorder(registry));
        String reused = nonce();

        // 第一次：nonce 是新的 ⇒ 走到签名校验，被 40107 拒（伪造尝试，不是重放）
        MockHttpServletRequest first = signedRequest();
        first.addHeader("X-NexusChain-Timestamp", String.valueOf(System.currentTimeMillis()));
        first.addHeader("X-NexusChain-Nonce", reused);
        assertFalse(interceptor.preHandle(first, new MockHttpServletResponse(), null));
        assertEquals(0.0, count(registry), "nonce 首次出现不算重放");

        // 第二次：同 nonce ⇒ 40106
        MockHttpServletRequest second = signedRequest();
        second.addHeader("X-NexusChain-Timestamp", String.valueOf(System.currentTimeMillis()));
        second.addHeader("X-NexusChain-Nonce", reused);
        assertFalse(interceptor.preHandle(second, new MockHttpServletResponse(), null));
        assertEquals(1.0, count(registry), "40106（重放）应计一次拦截");
    }

    // ==================== 不该计数的分支 ====================

    @Test
    @DisplayName("签名缺失/不匹配属伪造尝试，不混进重放计数")
    void signatureRejectionsDoNotCountAsReplay() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RequestSignatureInterceptor interceptor = interceptorWith(new SecurityMetricsRecorder(registry));

        // 40101：连签名头都没有
        assertFalse(interceptor.preHandle(new MockHttpServletRequest("POST", "/api/v1/payments"),
                new MockHttpServletResponse(), null));
        // 40107：时间戳新鲜、nonce 全新，但签名是错的
        MockHttpServletRequest forged = signedRequest();
        forged.addHeader("X-NexusChain-Timestamp", String.valueOf(System.currentTimeMillis()));
        forged.addHeader("X-NexusChain-Nonce", nonce());
        assertFalse(interceptor.preHandle(forged, new MockHttpServletResponse(), null));

        assertEquals(0.0, count(registry),
                "重放指标一旦被伪造类拒绝污染，就再也无法回答「有没有人在撞防重放窗口」这个问题");
    }

    @Test
    @DisplayName("记录器缺席时拦截照旧发生，且不抛异常")
    void worksWithoutMetricsRecorderBean() throws Exception {
        RequestSignatureInterceptor interceptor = interceptorWith(null);

        MockHttpServletRequest req = signedRequest();
        req.addHeader("X-NexusChain-Timestamp", String.valueOf(System.currentTimeMillis() - 400_000L));
        req.addHeader("X-NexusChain-Nonce", nonce());
        MockHttpServletResponse resp = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(req, resp, null));
        assertEquals(401, resp.getStatus(), "可观测性是旁路，缺席时拦截语义必须不变");
    }

    // ==================== 装配本身 ====================

    @Test
    @DisplayName("结构断言：多构造器里恰有一个标 @Autowired，且它就是收 ObjectProvider 的那个")
    void springConstructorIsTheMarkedOne() {
        long marked = java.util.Arrays.stream(RequestSignatureInterceptor.class.getConstructors())
                .filter(c -> c.isAnnotationPresent(
                        org.springframework.beans.factory.annotation.Autowired.class))
                .count();
        long withProviders = java.util.Arrays.stream(RequestSignatureInterceptor.class.getConstructors())
                .filter(c -> java.util.Arrays.stream(c.getParameterTypes())
                        .anyMatch(p -> p.getName().contains("ObjectProvider")))
                .count();

        assertEquals(1, marked,
                "多构造器 bean 漏标 @Autowired ⇒ Spring 静默挑无参/第一个，注入的依赖全空");
        assertEquals(withProviders, marked,
                "被标为装配入口的构造器必须就是收 ObjectProvider 的那个，否则新加的可选依赖传不进来");
    }

    // ==================== 辅助 ====================

    private RequestSignatureInterceptor interceptorWith(SecurityMetricsRecorder recorder) {
        ctx = new AnnotationConfigApplicationContext();
        if (recorder != null) {
            SecurityMetricsRecorder fixed = recorder;
            ctx.registerBean(SecurityMetricsRecorder.class, () -> fixed);
        }
        ctx.refresh();
        return new RequestSignatureInterceptor(
                SECRET, true, 180_000L, 16,
                ctx.getBeanProvider(ReplayNonceStore.class),
                ctx.getBeanProvider(SecurityMetricsRecorder.class));
    }

    /** 只带签名头的请求骨架：时间戳与 nonce 由各用例自己填，好让「哪条判据被拒」可读。 */
    private MockHttpServletRequest signedRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/payments");
        req.addHeader("X-NexusChain-Signature", "obviously-not-an-hmac");
        return req;
    }

    private static String nonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static double count(SimpleMeterRegistry registry) {
        assertNotNull(registry.find(METRIC).counter(),
                "计数器为 null 说明指标根本没注册——那时报 0 是「没接线」而不是「没发生」");
        return registry.find(METRIC).counter().count();
    }
}
