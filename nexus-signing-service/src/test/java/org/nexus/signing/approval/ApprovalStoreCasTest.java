package org.nexus.signing.approval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审批存储 CAS 原子性测试（P1-8 加固回归）。
 *
 * <p>回归背景：早期 tryMarkExecuting 为 get→判状态→save 三步非原子操作，
 * 并发调用可同时通过 APPROVED 检查导致同一审批双重签名（双重放款）。
 * 本测试锁定 {@link ApprovalStore#compareAndTransition} 的原子语义：
 * 并发迁移同一请求时，<b>有且仅有一个</b>调用方成功。</p>
 */
@DisplayName("审批存储 CAS 原子性")
class ApprovalStoreCasTest {

    private static final BigDecimal AMOUNT = new BigDecimal("50000");

    /** 构造一个处于指定状态的审批请求（包私有构造器，测试同包可用）。 */
    private static SigningApprovalRequest request(String requestId, SigningApprovalRequest.Status status) {
        return new SigningApprovalRequest(
                requestId, "pkFrom", "pkToHash", AMOUNT, "USDT", 2,
                Instant.now(), Instant.now().plusSeconds(3600),
                status, Set.of("a@nexus", "b@nexus"), Set.of(), "initiator");
    }

    @Test
    @DisplayName("1.Map存储：并发 CAS 同一请求，恰好一个成功")
    void mapStore_concurrentCas_exactlyOneWinner() throws Exception {
        MapApprovalStore store = new MapApprovalStore(new ConcurrentHashMap<>());
        store.put("req-1", request("req-1", SigningApprovalRequest.Status.APPROVED));

        int threads = 16;
        assertEquals(1, runConcurrentCas(store, threads),
                "并发 tryMarkExecuting 必须恰好成功一次，否则同一审批可能被双重签名");
    }

    @Test
    @DisplayName("2.Map存储：状态不匹配/请求不存在时 CAS 失败")
    void mapStore_casGuardConditions() {
        MapApprovalStore store = new MapApprovalStore(new ConcurrentHashMap<>());
        store.put("req-1", request("req-1", SigningApprovalRequest.Status.PENDING));

        assertFalse(store.compareAndTransition("req-1",
                SigningApprovalRequest.Status.APPROVED, SigningApprovalRequest.Status.EXECUTING),
                "PENDING 状态不允许直接迁移到 EXECUTING");
        assertFalse(store.compareAndTransition("req-missing",
                SigningApprovalRequest.Status.APPROVED, SigningApprovalRequest.Status.EXECUTING),
                "不存在的请求 CAS 必须失败");
        assertFalse(store.compareAndTransition(null,
                SigningApprovalRequest.Status.APPROVED, SigningApprovalRequest.Status.EXECUTING),
                "null requestId 必须失败");
    }

    @Test
    @DisplayName("3.Map存储：CAS 成功后状态为 EXECUTING")
    void mapStore_casTransitionVisible() {
        MapApprovalStore store = new MapApprovalStore(new ConcurrentHashMap<>());
        store.put("req-1", request("req-1", SigningApprovalRequest.Status.APPROVED));

        assertTrue(store.compareAndTransition("req-1",
                SigningApprovalRequest.Status.APPROVED, SigningApprovalRequest.Status.EXECUTING));
        assertEquals(SigningApprovalRequest.Status.EXECUTING, store.get("req-1").getStatus());
    }

    @Test
    @DisplayName("4.File存储：并发 CAS 恰好一个成功，且迁移落盘可恢复")
    void fileStore_concurrentCas_andPersistence(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("approval-cas.jsonl");
        FileBasedApprovalStore store = new FileBasedApprovalStore(file);
        store.put("req-1", request("req-1", SigningApprovalRequest.Status.APPROVED));

        int threads = 8;
        assertEquals(1, runConcurrentCas(store, threads),
                "文件存储的并发 CAS 同样必须恰好成功一次");
        assertEquals(SigningApprovalRequest.Status.EXECUTING, store.get("req-1").getStatus());

        // 模拟重启：新实例从同一文件恢复，状态应为 EXECUTING（迁移已持久化）
        FileBasedApprovalStore restored = new FileBasedApprovalStore(file);
        assertEquals(SigningApprovalRequest.Status.EXECUTING, restored.get("req-1").getStatus());
    }

    @Test
    @DisplayName("5.JPA存储：乐观锁冲突时 CAS 返回 false")
    void jpaStore_optimisticLockConflict_returnsFalse() {
        SigningApprovalRequestRepository repository = mock(SigningApprovalRequestRepository.class);
        JpaApprovalStore store = new JpaApprovalStore(repository);

        SigningApprovalRequestEntity entity = new SigningApprovalRequestEntity();
        org.nexus.signing.approval.JpaApprovalStore.applyDomain(entity,
                request("req-1", SigningApprovalRequest.Status.APPROVED));

        when(repository.findByRequestId("req-1")).thenReturn(java.util.Optional.of(entity));
        when(repository.save(any(SigningApprovalRequestEntity.class)))
                .thenThrow(new OptimisticLockingFailureException("concurrent modification"));

        assertFalse(store.compareAndTransition("req-1",
                        SigningApprovalRequest.Status.APPROVED, SigningApprovalRequest.Status.EXECUTING),
                "乐观锁冲突必须返回 false（由其他实例先行迁移），而非向上抛异常");
    }

    @Test
    @DisplayName("6.Service层：并发 tryMarkExecuting 同一审批，恰好一个成功")
    void service_tryMarkExecuting_concurrentExactlyOneWinner() throws Exception {
        var store = new MapApprovalStore(new ConcurrentHashMap<>());
        var service = new SigningApprovalService(
                mock(org.nexus.signing.mpc.MpcApprovalPolicy.class),
                mock(org.nexus.signing.audit.AuditLogService.class),
                store, null);
        store.put("req-1", request("req-1", SigningApprovalRequest.Status.APPROVED));

        assertEquals(1, runConcurrentCas(service::tryMarkExecuting, 16));
        assertEquals(SigningApprovalRequest.Status.EXECUTING, store.get("req-1").getStatus());
    }

    /** 并发对同一 requestId 执行 APPROVED→EXECUTING 迁移，返回成功次数。 */
    private int runConcurrentCas(ApprovalStore store, int threads) throws Exception {
        return runConcurrentCas(id -> store.compareAndTransition(id,
                SigningApprovalRequest.Status.APPROVED, SigningApprovalRequest.Status.EXECUTING), threads);
    }

    private int runConcurrentCas(java.util.function.Predicate<String> cas, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return cas.test("req-1");
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS), "工作线程未全部就绪");
            start.countDown();
            AtomicInteger winners = new AtomicInteger();
            for (Future<Boolean> f : futures) {
                if (Boolean.TRUE.equals(f.get(10, TimeUnit.SECONDS))) {
                    winners.incrementAndGet();
                }
            }
            return winners.get();
        } finally {
            pool.shutdownNow();
        }
    }
}
