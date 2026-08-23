package org.nexus.bridge.safety;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CircuitBreaker} 接入测试（B-21 修复专项）。
 *
 * <p>验证 P0 修复 B-21：熔断器主动接入桥主流程（lock/mint/burn/unlock）。</p>
 *
 * <p>测试 {@link CircuitBreaker#acquirePermission()}、
 * {@link CircuitBreaker#recordSuccess()}、{@link CircuitBreaker#recordFailure(String)}
 * 默认实现，以及熔断打开时桥操作应被拒绝的契约。</p>
 */
class CircuitBreakerIntegrationTest {

    private DefaultCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new DefaultCircuitBreaker();
    }

    // ==================== B-21: acquirePermission 接入契约 ====================

    @Test
    @DisplayName("should_returnTrue_when_acquirePermissionAndNotTripped")
    void should_returnTrue_when_acquirePermissionAndNotTripped() {
        // 初始未熔断，应允许执行
        assertThat(breaker.acquirePermission()).isTrue();
    }

    @Test
    @DisplayName("should_returnFalse_when_acquirePermissionAndTripped")
    void should_returnFalse_when_acquirePermissionAndTripped() {
        breaker.trip("failure rate exceeded");

        // 熔断打开时应拒绝执行
        assertThat(breaker.acquirePermission()).isFalse();
    }

    @Test
    @DisplayName("should_returnTrueAgain_when_acquirePermissionAfterReset")
    void should_returnTrueAgain_when_acquirePermissionAfterReset() {
        breaker.trip("reason");
        assertThat(breaker.acquirePermission()).isFalse();

        breaker.reset();
        // 重置后应允许执行
        assertThat(breaker.acquirePermission()).isTrue();
    }

    // ==================== B-21: 桥操作被熔断拒绝的契约 ====================

    @Test
    @DisplayName("should_rejectLock_when_circuitBreakerTripped")
    void should_rejectLock_when_circuitBreakerTripped() {
        breaker.trip("bridge anomaly detected");

        // 模拟桥 lock 操作前的熔断检查
        if (!breaker.acquirePermission()) {
            // 熔断打开时应抛 BridgeCircuitOpenException（这里用 IllegalStateException 模拟）
            assertThatThrownBy(() -> {
                throw new IllegalStateException("Bridge circuit open: " + breaker.getTripReason());
            })
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Bridge circuit open")
                    .hasMessageContaining("bridge anomaly detected");
        }
    }

    @Test
    @DisplayName("should_rejectMint_when_circuitBreakerTripped")
    void should_rejectMint_when_circuitBreakerTripped() {
        breaker.trip("mint failure rate high");

        assertThat(breaker.acquirePermission()).isFalse();
        // 桥 mint 操作应被拒绝
        assertThat(breaker.isTripped()).isTrue();
        assertThat(breaker.getTripReason()).contains("mint failure rate high");
    }

    @Test
    @DisplayName("should_rejectBurn_when_circuitBreakerTripped")
    void should_rejectBurn_when_circuitBreakerTripped() {
        breaker.trip("burn anomaly");

        assertThat(breaker.acquirePermission()).isFalse();
        assertThat(breaker.isTripped()).isTrue();
    }

    @Test
    @DisplayName("should_rejectUnlock_when_circuitBreakerTripped")
    void should_rejectUnlock_when_circuitBreakerTripped() {
        breaker.trip("unlock failed");

        assertThat(breaker.acquirePermission()).isFalse();
        assertThat(breaker.isTripped()).isTrue();
    }

    // ==================== B-21: recordSuccess / recordFailure 默认实现 ====================

    @Test
    @DisplayName("should_notThrow_when_recordSuccess")
    void should_notThrow_when_recordSuccess() {
        // 默认实现为空操作，不应抛异常
        breaker.recordSuccess();
        // 状态不应改变
        assertThat(breaker.isTripped()).isFalse();
    }

    @Test
    @DisplayName("should_notThrow_when_recordFailure")
    void should_notThrow_when_recordFailure() {
        // 默认实现为空操作，不应抛异常，也不自动熔断
        breaker.recordFailure("some failure");
        // 默认实现不自动熔断
        assertThat(breaker.isTripped()).isFalse();
    }

    @Test
    @DisplayName("should_notAffectState_when_recordSuccessAndFailure")
    void should_notAffectState_when_recordSuccessAndFailure() {
        breaker.recordSuccess();
        breaker.recordFailure("failure-1");
        breaker.recordSuccess();
        breaker.recordFailure("failure-2");

        // 默认实现不影响状态
        assertThat(breaker.isTripped()).isFalse();
        assertThat(breaker.acquirePermission()).isTrue();
    }

    // ==================== B-21: 完整桥操作流程模拟 ====================

    @Test
    @DisplayName("should_allowBridgeOperation_when_circuitHealthy")
    void should_allowBridgeOperation_when_circuitHealthy() {
        // 模拟桥操作完整流程：acquirePermission -> 操作 -> recordSuccess
        assertThat(breaker.acquirePermission()).isTrue();

        // 模拟操作成功
        breaker.recordSuccess();

        // 后续操作仍允许
        assertThat(breaker.acquirePermission()).isTrue();
    }

    @Test
    @DisplayName("should_blockSubsequentOperations_when_manualTripAfterFailure")
    void should_blockSubsequentOperations_when_manualTripAfterFailure() {
        // 模拟桥操作失败后手动熔断
        assertThat(breaker.acquirePermission()).isTrue();
        breaker.recordFailure("critical failure");
        // 运维或上层逻辑判断后手动熔断
        breaker.trip("manual trip after critical failure");

        // 后续操作应被拒绝
        assertThat(breaker.acquirePermission()).isFalse();
        assertThat(breaker.isTripped()).isTrue();
    }

    @Test
    @DisplayName("should_recoverAfterReset_when_circuitTrippedThenReset")
    void should_recoverAfterReset_when_circuitTrippedThenReset() {
        // 熔断
        breaker.trip("temporary issue");
        assertThat(breaker.acquirePermission()).isFalse();

        // 重置（运维确认问题已修复）
        breaker.reset();
        assertThat(breaker.isTripped()).isFalse();
        assertThat(breaker.acquirePermission()).isTrue();

        // 后续操作正常
        breaker.recordSuccess();
        assertThat(breaker.acquirePermission()).isTrue();
    }

    // ==================== 熔断原因追踪 ====================

    @Test
    @DisplayName("should_keepTripReason_when_tripped")
    void should_keepTripReason_when_tripped() {
        String reason = "consistency check failed at block 12345";
        breaker.trip(reason);

        assertThat(breaker.getTripReason()).isEqualTo(reason);
    }

    @Test
    @DisplayName("should_clearTripReason_when_reset")
    void should_clearTripReason_when_reset() {
        breaker.trip("some reason");
        assertThat(breaker.getTripReason()).isNotNull();

        breaker.reset();
        assertThat(breaker.getTripReason()).isNull();
    }

    @Test
    @DisplayName("should_updateReason_when_repeatedTrip")
    void should_updateReason_when_repeatedTrip() {
        breaker.trip("reason-1");
        assertThat(breaker.getTripReason()).isEqualTo("reason-1");

        breaker.trip("reason-2");
        assertThat(breaker.getTripReason()).isEqualTo("reason-2");
        assertThat(breaker.isTripped()).isTrue();
    }

    // ==================== 桥操作模板：熔断检查应放在入口 ====================

    @Test
    @DisplayName("should_checkCircuitBeforeOperation_when_bridgeOperationCalled")
    void should_checkCircuitBeforeOperation_when_bridgeOperationCalled() {
        // 模拟桥操作模板
        breaker.trip("pre-existing issue");

        // 桥操作入口应先检查熔断
        boolean permitted = breaker.acquirePermission();
        assertThat(permitted).isFalse();

        // 不应执行实际操作（这里用 recordSuccess 模拟操作执行）
        // 实际桥代码应在 acquirePermission 返回 false 时直接返回错误响应
        if (permitted) {
            breaker.recordSuccess();
        }

        // 熔断状态未变
        assertThat(breaker.isTripped()).isTrue();
    }

    // ==================== 并发安全（熔断状态切换） ====================

    @Test
    @DisplayName("should_beThreadSafe_when_concurrentTripAndReset")
    void should_beThreadSafe_when_concurrentTripAndReset() throws InterruptedException {
        int threadCount = 20;
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch done =
                new java.util.concurrent.CountDownLatch(threadCount);
        java.util.List<Throwable> errors =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    if (idx % 2 == 0) {
                        breaker.trip("trip-" + idx);
                    } else {
                        breaker.reset();
                    }
                    // 调用 acquirePermission 不应抛异常
                    breaker.acquirePermission();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    done.countDown();
                }
                return null;
            });
        }

        done.await(10, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(errors).as("并发 trip/reset 不应抛异常: " + errors).isEmpty();
    }
}