package org.nexus.gateway.orchestration.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.orchestration.connector.*;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.repository.OrchestratedPaymentRepository;
import org.nexus.gateway.orchestration.routing.RoutingDecision;
import org.nexus.gateway.orchestration.routing.RoutingEngine;
import org.nexus.gateway.orchestration.routing.ai.MetricsCollector;
import org.nexus.gateway.ratelimit.IdempotencyStore;
import org.nexus.gateway.ratelimit.InMemoryIdempotencyStore;
import org.nexus.gateway.risk.PaymentRiskService;
import org.nexus.gateway.risk.RiskDecision;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * P1-1（2026-10-12）：支付受理幂等的并发正确性。
 *
 * <p>这里刻意用**真实** {@link OrchestrationIdempotencyStore} +
 * {@link InMemoryIdempotencyStore}（CHM CAS）而不是 mock —— mock 的 {@code tryClaim}
 * 只会返回固定值，并发裁决根本没被 exercised，测试可以对着有缺陷的实现恒绿。
 * repo 也做成"按 id 可回查"的假存储，否则重放路径会落到"映射在、记录不可见"
 * 的 ghost 分支，测不到真正的重放。</p>
 */
class OrchestrationIdempotencyConcurrencyTest {

    private OrchestratedPaymentRepository repo;
    private RoutingEngine routingEngine;
    private OrchestrationIdempotencyStore store;
    private PaymentConnector connector;
    private final AtomicInteger connectorCalls = new AtomicInteger();
    private final AtomicInteger routeCalls = new AtomicInteger();
    private final Map<String, OrchestratedPayment> db = new ConcurrentHashMap<>();
    private volatile boolean routeBoom;

    private OrchestrationService service;

    private static RoutingDecision decision(List<PaymentConnector> connectors) {
        return new RoutingDecision(connectors, "test-rule", "PRIORITY",
                List.of(), new LinkedHashMap<>(), null, null, null);
    }

    @BeforeEach
    void setUp() {
        repo = mock(OrchestratedPaymentRepository.class);
        routingEngine = mock(RoutingEngine.class);
        PaymentRiskService riskService = mock(PaymentRiskService.class);
        connector = mock(PaymentConnector.class);
        store = new OrchestrationIdempotencyStore(new InMemoryIdempotencyStore());

        when(riskService.evaluatePayment(any())).thenReturn(RiskDecision.APPROVED);
        when(routingEngine.resolveDetailed(any())).thenAnswer(inv -> {
            if (routeBoom && routeCalls.incrementAndGet() == 1) {
                throw new RuntimeException("routing boom");
            }
            return decision(List.of(connector));
        });
        when(connector.getId()).thenReturn("chain");
        when(connector.createPayment(any())).thenAnswer(inv -> {
            connectorCalls.incrementAndGet();
            // 拉宽竞态窗口：未修复的实现下，所有线程都会在此之前读到 null 并各自动账
            Thread.sleep(30);
            return ConnectorPaymentResult.ok("conn-1", PaymentStatus.SUCCEEDED, "0xTx");
        });
        when(repo.save(any())).thenAnswer(inv -> {
            OrchestratedPayment p = inv.getArgument(0);
            db.put(p.getId(), p);
            return p;
        });
        when(repo.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(db.get((String) inv.getArgument(0))));

        service = new OrchestrationService(repo, routingEngine, mock(ConnectorRegistry.class),
                store, mock(OrchestrationWebhookDispatcher.class), riskService, null,
                mock(MetricsCollector.class), mock(ApplicationEventPublisher.class));
    }

    @Test
    @DisplayName("并发同 requestId：渠道只被调用一次，其余请求拿 409 而不是二次动账")
    void concurrentSameRequestIdChargesOnce() throws Exception {
        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<OrchestratedPayment>> futures = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return service.createPayment(100L, 1000, "NEX", "desc", null, null, null,
                        "req-concurrent");
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "线程未就位");
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        int accepted = 0;
        int conflict = 0;
        for (Future<OrchestratedPayment> f : futures) {
            try {
                OrchestratedPayment p = f.get();
                assertNotNull(p);
                assertEquals(OrchPaymentStatus.SUCCEEDED, p.getStatus());
                accepted++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertInstanceOf(IllegalStateException.class, e.getCause(),
                        "并发败者必须是 409 语义的 IllegalStateException");
                conflict++;
            }
        }

        assertEquals(1, accepted, "只有占位胜者可以动账");
        assertEquals(n - 1, conflict, "其余请求必须被拒");
        assertEquals(1, connectorCalls.get(), "核心断言：渠道/链上动账只发生一次");
    }

    @Test
    @DisplayName("胜者落库后：同 requestId 重试重放同一笔，映射已从占位换成真实 id")
    void winnerMappingIsReplayable() {
        OrchestratedPayment first = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, "req-replay");
        assertEquals(1, connectorCalls.get());
        assertEquals(first.getId(), store.checkDuplicate("req-replay"),
                "persist() 必须把 IN_FLIGHT 换成真实 paymentId");

        OrchestratedPayment replay = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, "req-replay");
        assertEquals(first.getId(), replay.getId(), "重试必须拿到同一笔");
        assertEquals(1, connectorCalls.get(), "重放不得再次动账");
    }

    @Test
    @DisplayName("异常路径必须释放占位，否则该 requestId 永久 409 且无法自愈")
    void exceptionReleasesClaim() {
        routeBoom = true;
        assertThrows(RuntimeException.class, () -> service.createPayment(100L, 1000, "NEX",
                "d", null, null, null, "req-release"));
        assertEquals(0, connectorCalls.get(), "异常发生在渠道之前，不应已动账");

        // 占位已释放 → 同 key 可再次尝试并成功
        routeBoom = false;
        OrchestratedPayment second = service.createPayment(100L, 1000, "NEX", "d",
                null, null, null, "req-release");
        assertEquals(OrchPaymentStatus.SUCCEEDED, second.getStatus());
        assertEquals(1, connectorCalls.get(), "全程只动账一次");
    }

    @Test
    @DisplayName("store 语义：占位不当作已完成映射；已完成映射不被释放抹掉")
    void storeClaimSemantics() {
        IdempotencyStore backing = new InMemoryIdempotencyStore();
        OrchestrationIdempotencyStore s = new OrchestrationIdempotencyStore(backing);

        assertTrue(s.tryClaim("r1"));
        assertNull(s.checkDuplicate("r1"), "占位不能被当成已完成的 paymentId 返回");
        assertFalse(s.tryClaim("r1"), "占位期间第二个同 key 请求抢不到");

        s.releaseClaim("r1");
        assertTrue(s.tryClaim("r1"), "释放后应可重新占位");

        s.record("r1", "pay_done");
        s.releaseClaim("r1");
        assertEquals("pay_done", s.checkDuplicate("r1"), "已完成映射不得被条件释放抹掉");
    }

    @Test
    @DisplayName("无 requestId 的请求不参与占位")
    void blankRequestIdBypassesClaim() {
        assertTrue(store.tryClaim(null));
        assertTrue(store.tryClaim(" "));
        assertNull(store.checkDuplicate(null));
        store.releaseClaim(null);
    }
}
