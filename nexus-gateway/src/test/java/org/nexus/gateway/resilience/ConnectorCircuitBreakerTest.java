package org.nexus.gateway.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.Metrics;
import org.nexus.gateway.orchestration.connector.FailureKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ConnectorCircuitBreaker} 单元测试 — 测试熔断器状态转换与核心行为。
 */
class ConnectorCircuitBreakerTest {

    private ConnectorCircuitBreakerConfig config;
    private ConnectorCircuitBreaker circuitBreaker;

    // 模拟 PSP 拒绝的真实异常：recordFailure 必须保留调用方传入的异常类型，
    // 不得像旧实现那样内部 new 一个 RuntimeException 把真实原因抹掉
    private static final IllegalArgumentException PSP_DECLINED =
            new IllegalArgumentException("psp declined");

    @BeforeEach
    void setUp() {
        config = new ConnectorCircuitBreakerConfig();
        // 配置低阈值以便快速触发熔断
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties defaultProps =
                config.getDefaultConfig();
        defaultProps.setFailureRateThreshold(50f);
        defaultProps.setSlidingWindowSize(4);
        defaultProps.setMinimumNumberOfCalls(2);
        defaultProps.setWaitDurationInOpenState(java.time.Duration.ofSeconds(1));

        circuitBreaker = new ConnectorCircuitBreaker(config);
    }

    @Test
    @DisplayName("新建熔断器初始状态为 CLOSED")
    void newCircuitBreaker_isClosed() {
        CircuitBreaker.State state = circuitBreaker.getConnectorState("chain");
        assertEquals(CircuitBreaker.State.DISABLED, state,
                "未创建的 connector 状态应为 DISABLED");

        // 触发创建
        circuitBreaker.getOrCreate("chain", "chain");
        state = circuitBreaker.getConnectorState("chain");
        assertEquals(CircuitBreaker.State.CLOSED, state,
                "新创建的熔断器应为 CLOSED");
    }

    @Test
    @DisplayName("连续失败达到阈值后状态转换为 OPEN")
    void consecutiveFailures_openCircuit() {
        circuitBreaker.getOrCreate("stripe", "stripe");

        // slidingWindowSize=4, minimumNumberOfCalls=2, failureRateThreshold=50%
        // 2次失败 + 0次成功 = 100% 失败率 > 50% → OPEN
        circuitBreaker.recordFailure("stripe", 300_000_000L, PSP_DECLINED);
        circuitBreaker.recordFailure("stripe", 300_000_000L, PSP_DECLINED);

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getConnectorState("stripe"),
                "连续失败后熔断器应为 OPEN");
    }

    @Test
    @DisplayName("成功调用保持 CLOSED 状态")
    void successCall_staysClosed() {
        circuitBreaker.getOrCreate("consortium", "consortium");

        circuitBreaker.recordSuccess("consortium", 50_000_000L);
        circuitBreaker.recordSuccess("consortium", 50_000_000L);

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getConnectorState("consortium"),
                "成功调用后熔断器应保持 CLOSED");
    }

    @Test
    @DisplayName("混合调用（成功+失败）低于阈值保持 CLOSED")
    void mixedCalls_belowThreshold_staysClosed() {
        // 调整阈值：60% 失败率才触发
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties stripeProps = new ConnectorCircuitBreakerConfig.CircuitBreakerProperties();
        stripeProps.setFailureRateThreshold(60f);
        stripeProps.setSlidingWindowSize(4);
        stripeProps.setMinimumNumberOfCalls(2);
        config.getInstances().put("stripe", stripeProps);

        circuitBreaker.getOrCreate("stripe", "stripe");

        // 1成功 + 1失败 = 50% 失败率 < 60% → CLOSED
        circuitBreaker.recordSuccess("stripe", 10_000_000L);
        circuitBreaker.recordFailure("stripe", 300_000_000L, PSP_DECLINED);

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getConnectorState("stripe"),
                "失败率低于阈值时应保持 CLOSED");
    }

    @Test
    @DisplayName("isCircuitOpen 正确判断熔断状态")
    void isCircuitOpen_correctStatus() {
        circuitBreaker.getOrCreate("chain", "chain");
        assertFalse(circuitBreaker.isCircuitOpen("chain"),
                "CLOSED 状态时 isCircuitOpen 应为 false");

        circuitBreaker.recordFailure("chain", 300_000_000L, PSP_DECLINED);
        circuitBreaker.recordFailure("chain", 300_000_000L, PSP_DECLINED);

        assertTrue(circuitBreaker.isCircuitOpen("chain"),
                "OPEN 状态时 isCircuitOpen 应为 true");
    }

    @Test
    @DisplayName("reset 将熔断器强制恢复到 CLOSED")
    void reset_returnsToClosed() {
        circuitBreaker.getOrCreate("chain", "chain");

        // 触发熔断
        circuitBreaker.recordFailure("chain", 300_000_000L, PSP_DECLINED);
        circuitBreaker.recordFailure("chain", 300_000_000L, PSP_DECLINED);
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getConnectorState("chain"));

        // 重置
        circuitBreaker.reset("chain");
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getConnectorState("chain"),
                "reset 后熔断器应为 CLOSED");
    }

    @Test
    @DisplayName("getRegisteredConnectors 返回所有已注册的 connector 实例标识")
    void getRegisteredConnectors_returnsAll() {
        circuitBreaker.getOrCreate("chain", "chain");
        circuitBreaker.getOrCreate("stripe", "stripe");
        circuitBreaker.getOrCreate("consortium", "consortium");

        assertTrue(circuitBreaker.getRegisteredConnectors().contains("chain"));
        assertTrue(circuitBreaker.getRegisteredConnectors().contains("stripe"));
        assertTrue(circuitBreaker.getRegisteredConnectors().contains("consortium"));
        assertEquals(3, circuitBreaker.getRegisteredConnectors().size());
    }

    @Test
    @DisplayName("按 connector 类型独立覆盖配置生效")
    void perConnectorOverride_takesEffect() {
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties chainProps = new ConnectorCircuitBreakerConfig.CircuitBreakerProperties();
        chainProps.setFailureRateThreshold(30f);
        chainProps.setWaitDurationInOpenState(java.time.Duration.ofSeconds(60));
        config.getInstances().put("chain", chainProps);

        ConnectorCircuitBreakerConfig.CircuitBreakerProperties effective = config.getEffectiveConfig("chain");
        assertEquals(30f, effective.getFailureRateThreshold(), "chain 覆盖的 failureRateThreshold 应为 30");
        assertEquals(60, effective.getWaitDurationInOpenState().getSeconds(), "chain 覆盖的 waitDuration 应为 60s");
        // 未覆盖的字段回退到默认值
        assertEquals(10, effective.getSlidingWindowSize(), "未覆盖的 slidingWindowSize 应回退到默认值 10");
    }

    @Test
    @DisplayName("未配置覆盖的 connector 使用默认配置")
    void noOverride_usesDefault() {
        ConnectorCircuitBreakerConfig.CircuitBreakerProperties effective = config.getEffectiveConfig("unknown_connector");
        assertEquals(50f, effective.getFailureRateThreshold(), "默认 failureRateThreshold 应为 50");
        assertEquals(1, effective.getWaitDurationInOpenState().getSeconds(), "默认 waitDuration 应为 1s（setUp 中修改）");
    }

    @Test
    @DisplayName("同类型不同 id 的 connector 独立分桶，互不牵连")
    void sameTypeDifferentIds_independentBuckets() {
        // 两个同为 stripe 类型的 PSP 实例：a 熔断不得牵连 b
        circuitBreaker.getOrCreate("psp-a", "stripe");
        circuitBreaker.getOrCreate("psp-b", "stripe");

        circuitBreaker.recordFailure("psp-a", 300_000_000L, PSP_DECLINED);
        circuitBreaker.recordFailure("psp-a", 300_000_000L, PSP_DECLINED);

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getConnectorState("psp-a"),
                "psp-a 连续失败后应为 OPEN");
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getConnectorState("psp-b"),
                "同类型的 psp-b 不应被 psp-a 牵连");
    }

    @Test
    @DisplayName("record 传真实耗时与真实异常，null cause 被拒收")
    void record_callsCarryRealDurationAndCause() {
        circuitBreaker.getOrCreate("stripe", "stripe");

        circuitBreaker.recordSuccess("stripe", 150_000_000L);
        circuitBreaker.recordFailure("stripe", 300_000_000L, PSP_DECLINED);

        Metrics metrics = circuitBreaker.getCircuitBreaker("stripe").getMetrics();
        assertEquals(1, metrics.getNumberOfSuccessfulCalls(), "成功数应为 1");
        assertEquals(1, metrics.getNumberOfFailedCalls(), "失败数应为 1");

        assertThrows(NullPointerException.class,
                () -> circuitBreaker.recordFailure("stripe", 10_000_000L, null),
                "cause 为 null 应被拒收，不得把空指针传进 Resilience4j");
    }

    @Test
    @DisplayName("对未创建的 connector 调 record 不自动建桶")
    void record_unknownId_noBucketCreated() {
        circuitBreaker.recordSuccess("ghost", 10_000_000L);
        circuitBreaker.recordFailure("ghost", 10_000_000L, PSP_DECLINED);

        assertEquals(CircuitBreaker.State.DISABLED, circuitBreaker.getConnectorState("ghost"),
                "未创建的 connector 状态应为 DISABLED");
        assertNull(circuitBreaker.getCircuitBreaker("ghost"), "record 不应自动创建熔断器");
        assertFalse(circuitBreaker.getRegisteredConnectors().contains("ghost"),
                "getRegisteredConnectors 不应包含未创建的 connector");
    }

    // === recordClassifiedFailure：仅 UNAVAILABLE 计入（板 20261110-1511 裁决 ③）===

    @Test
    @DisplayName("recordClassifiedFailure: UNAVAILABLE 计入失败并可达 OPEN")
    void recordClassifiedFailure_unavailableCountsTowardOpen() {
        circuitBreaker.getOrCreate("stripe", "stripe");

        circuitBreaker.recordClassifiedFailure("stripe", 300_000_000L, FailureKind.UNAVAILABLE);
        circuitBreaker.recordClassifiedFailure("stripe", 300_000_000L, FailureKind.UNAVAILABLE);

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getConnectorState("stripe"),
                "UNAVAILABLE 是唯一计入熔断的分类，连续两次应达阈值打开");
    }

    @Test
    @DisplayName("recordClassifiedFailure: DECLINE/CONFIG/null 一律不计入，熔断器保持 CLOSED")
    void recordClassifiedFailure_nonUnavailableNeverOpens() {
        circuitBreaker.getOrCreate("stripe", "stripe");

        for (int i = 0; i < 10; i++) {
            circuitBreaker.recordClassifiedFailure("stripe", 300_000_000L, FailureKind.DECLINE);
            circuitBreaker.recordClassifiedFailure("stripe", 300_000_000L, FailureKind.CONFIG);
            circuitBreaker.recordClassifiedFailure("stripe", 300_000_000L, null);
        }

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getConnectorState("stripe"),
                "连续业务拒绝/配置错误/无分类都不得熔掉健康通道（资金路径硬规则）");
        assertEquals(0, circuitBreaker.getCircuitBreaker("stripe").getMetrics().getNumberOfFailedCalls(),
                "不计入的失败不得进入 Resilience4j 滑动窗口");
    }
}