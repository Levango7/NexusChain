package org.nexus.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.gateway.dto.CreateOrderRequest;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.ratelimit.IdempotencyStore;
import org.nexus.gateway.ratelimit.InMemoryIdempotencyStore;
import org.nexus.gateway.repository.MerchantRepository;
import org.nexus.gateway.repository.PaymentOrderRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P1 回归测试（2026-09-17）：订单创建的幂等竞态。
 *
 * <p>原缺陷：{@code createOrder} 为 get → 创建 → put 的 check-then-act。
 * 并发下两个使用相同 idempotencyKey 的请求会同时读到 null，各自建单 ——
 * 幂等完全失效。修法为「原子占位 → 创建 → 回填」，本类验证各分支。</p>
 *
 * <p>测试策略：除并发用例外，其余均使用**确定性**断言（预置 store 状态），
 * 避免依赖线程时序造成偶发失败。</p>
 */
class OrderIdempotencyRaceTest {

    private PaymentOrderRepository orderRepository;
    private MerchantRepository merchantRepository;
    private IdempotencyStore idempotencyStore;
    private OrderServiceImpl service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(PaymentOrderRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        // 使用真实的 InMemoryIdempotencyStore，以便真正走到 putIfAbsent 的原子语义
        idempotencyStore = new InMemoryIdempotencyStore();
        service = new OrderServiceImpl(orderRepository, merchantRepository,
                new GatewayConfig(), idempotencyStore);

        AtomicLong seq = new AtomicLong(1);
        when(orderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> {
            PaymentOrder o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(seq.getAndIncrement());
            }
            return o;
        });
    }

    private static CreateOrderRequest request(String idempotencyKey) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setIdempotencyKey(idempotencyKey);
        req.setMerchantId("1");
        req.setAmount(java.math.BigDecimal.TEN);
        req.setNotifyUrl("https://merchant.example/notify");
        // 显式指定有效期，避免依赖 GatewayConfig 的默认值
        req.setExpiryMinutes(30);
        return req;
    }

    // === 原子占位本身 ===

    @Test
    @DisplayName("putIfAbsent: 并发争抢同一 key 只有 1 个胜者")
    void putIfAbsentIsAtomic() throws Exception {
        IdempotencyStore store = new InMemoryIdempotencyStore();
        int threads = 64;
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    if (store.putIfAbsent("same-key", "v")) {
                        winners.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, winners.get(), "原子占位必须只允许一个胜者");
    }

    // === createOrder 各分支（确定性） ===

    @Test
    @DisplayName("已完成映射：直接返回既有订单，不再创建")
    void completedKeyReturnsExistingOrder() {
        PaymentOrder existing = new PaymentOrder();
        existing.setId(42L);
        when(orderRepository.findById(42L)).thenReturn(Optional.of(existing));
        idempotencyStore.put("key-done", "42");

        PaymentOrder result = service.createOrder(request("key-done"));

        assertSame(existing, result);
        verify(orderRepository, never()).save(any(PaymentOrder.class));
    }

    @Test
    @DisplayName("占位中（IN_FLIGHT）：拒绝且绝不创建第二单")
    void inFlightKeyIsRejectedWithoutCreating() {
        // 模拟另一并发请求已占位但尚未回填真实订单号
        idempotencyStore.putIfAbsent("key-inflight", "__IN_FLIGHT__");

        assertThrows(IllegalStateException.class, () -> service.createOrder(request("key-inflight")));

        verify(orderRepository, never()).save(any(PaymentOrder.class));
    }

    @Test
    @DisplayName("创建失败：占位被释放，同一 key 可重试成功")
    void failureReleasesClaimAllowingRetry() {
        when(orderRepository.save(any(PaymentOrder.class)))
                .thenThrow(new RuntimeException("db unavailable"));

        CreateOrderRequest req = request("key-retry");
        assertThrows(RuntimeException.class, () -> service.createOrder(req));

        // 占位必须已释放：否则该 key 永久卡在 IN_FLIGHT，后续请求全部被拒且无法自愈
        assertNull(idempotencyStore.get("key-retry"), "失败后占位必须被清除");

        // 恢复后重试应成功（而不是再次抛「in flight」）
        reset(orderRepository);
        AtomicLong seq = new AtomicLong(100);
        when(orderRepository.save(any(PaymentOrder.class))).thenAnswer(inv -> {
            PaymentOrder o = inv.getArgument(0);
            if (o.getId() == null) {
                o.setId(seq.getAndIncrement());
            }
            return o;
        });
        assertNotNull(service.createOrder(req), "释放占位后重试必须成功");
    }

    @Test
    @DisplayName("并发同 key：无论竞争结果如何，最多创建一次")
    void concurrentSameKeyCreatesAtMostOneOrder() throws Exception {
        int threads = 16;
        CreateOrderRequest req = request("key-concurrent");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        service.createOrder(req);
                    } catch (IllegalStateException expected) {
                        // 竞争对手仍在创建中 —— 这是允许且期望的结果
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // 核心不变量：无论时序如何，save 只能发生一次
        verify(orderRepository, times(1)).save(any(PaymentOrder.class));
    }

    @Test
    @DisplayName("无 idempotencyKey：不占用存储，正常创建")
    void withoutKeyCreatesNormally() {
        assertNotNull(service.createOrder(request(null)));
        verify(orderRepository, times(1)).save(any(PaymentOrder.class));
    }
}
